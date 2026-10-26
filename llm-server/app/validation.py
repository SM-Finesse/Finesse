"""응답 검증 (생성기 출력 -> 백엔드로 보내기 전). light 와 heavy.

근거: LLM/AI 파트 설계 v1.2 5.2절(응답 스키마), 6절·9.2절(처리 규칙),
      12.1절(형식 준수율: 유효 JSON + 키 구성 + 정확히 3개 + stat이 11개 키 안)

두 단계로 나눈다.
  1) parse_light_output : 형식(구조) 검사. 통과 못 하면 OutputFormatError.
     find_prompt_echo   : 시스템 프롬프트를 베낀 출력 찾기 (있으면 서버가 502 → 백엔드 재요청)
     normalize_stats    : 점 경로 stat 을 평탄 키로 정리
  2) check_light_output : 내용 규칙 검사. 문제 목록만 돌려주고 응답은 고치지 않는다.
     (3개 초과 자르기·11개 밖 제외·재요청은 백엔드 몫 — v1.2 6절)
"""
import json
import re

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.names import normalize_names
from app.schemas import CANDIDATE_KEYS


class _StrictOut(BaseModel):
    # forbid: 정해진 키 외의 키가 오면 형식 오류 (예: 구버전 summary_hint)
    # str_strip_whitespace: 공백만 있는 문장은 빈 문장으로 본다
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Highlight(_StrictOut):
    stat: str
    sentence: str = Field(min_length=1)


class LightResponse(_StrictOut):
    light_summary: str = Field(min_length=1)
    highlights: list[Highlight]


class OutputFormatError(ValueError):
    """생성기 출력이 응답 형식에 맞지 않을 때."""


def _load_json(text: str, lenient: bool) -> object:
    """문자열을 JSON으로 읽는다.

    lenient=True: 첫 번째 '{' 부터 JSON 객체 하나만 읽고, 그 뒤에 붙은 글은 무시한다.
      (베이스 Qwen 이 JSON 뒤에 설명·두 번째 JSON 을 덧붙여 502 가 나던 문제, 10/7 11번 로그 "Extra data")
    lenient=False: 전체가 정확히 JSON 하나여야 한다 (10/7 이전 기준, 비교 측정용).
    """
    if not lenient:
        return json.loads(text)
    start = text.find("{")
    if start < 0:
        raise json.JSONDecodeError("JSON 객체가 없음", text, 0)
    obj, _end = json.JSONDecoder().raw_decode(text, start)
    return obj


def parse_light_output(text: str, lenient: bool = True) -> LightResponse:
    """생성기가 낸 문자열을 응답 객체로 바꾼다. 형식이 틀리면 OutputFormatError."""
    try:
        data = _load_json(text, lenient)
    except json.JSONDecodeError as e:
        raise OutputFormatError(f"유효한 JSON이 아님: {e}") from e
    try:
        return LightResponse.model_validate(data)
    except ValidationError as e:
        first = e.errors()[0]
        raise OutputFormatError(f"키 구성 오류: {first['loc']} {first['msg']}") from e


# 시스템 프롬프트에만 있는 문구. 출력에 이게 있으면 프롬프트를 베낀 것으로 본다.
# (10/7 백엔드 보고: icly 결과 총평에 "당신은 테트리스 게임 TETR.IO의 ..." 가 그대로 나옴)
PROMPT_ECHO_MARKERS = (
    "당신은 테트리스",
    "어시스턴트입니다",
    "[해석 규칙]",
    "[선정·서술 규칙]",
    "[출력 형식]",
    "JSON으로만 응답",
    "2-3문장 요약",
    "지표 키",
)


def find_prompt_echo(resp: LightResponse) -> list[str]:
    """요약·문장에서 발견된 프롬프트 문구 목록. 비어 있지 않으면 형식 오류(502)로 돌려 재요청을 받는다."""
    texts = [resp.light_summary, *(h.sentence for h in resp.highlights)]
    return [m for m in PROMPT_ECHO_MARKERS if any(m in t for t in texts)]


def normalize_stats(resp: LightResponse) -> list[str]:
    """점 경로 stat(예: playstyle_relative.delta_plonk)을 평탄 키(delta_plonk)로 고친다.

    마지막 조각이 후보 11개 중 하나일 때만 고친다. 고친 목록을 돌려준다(로그용).
    ※ v1.2 5.2절은 "11개 밖 stat 은 백엔드가 그 하이라이트만 제외"로 되어 있음.
      서버에서 미리 고치는 것은 10/8 추가 — 팀 확인 필요.
    """
    changed = []
    for h in resp.highlights:
        if h.stat not in CANDIDATE_KEYS and "." in h.stat:
            tail = h.stat.rsplit(".", 1)[1]
            if tail in CANDIDATE_KEYS:
                changed.append(f"{h.stat}->{tail}")
                h.stat = tail
    return changed


def process_light_output(text: str, available: dict[str, float]) -> tuple[LightResponse, list[str], list[str]]:
    """서버가 light 출력에 하는 처리 전체 (서버와 실험 스크립트가 같은 함수를 쓴다).

    형식 검사(앞 JSON 만) → 프롬프트 베낌 검사 → stat 키 정리 → 내용 규칙 검사.
    형식 문제면 OutputFormatError. 아니면 (응답, 내용 문제 목록, 키 정리 목록).
    """
    resp = parse_light_output(text)
    echo = find_prompt_echo(resp)
    if echo:
        raise OutputFormatError(f"시스템 프롬프트를 베낀 출력: {echo}")
    changed = normalize_stats(resp)
    return resp, check_light_output(resp, available), changed


def check_light_output(resp: LightResponse, available: dict[str, float]) -> list[str]:
    """내용 규칙 위반 목록을 돌려준다. 빈 목록이면 통과.

    available: 요청에서 값이 있던 후보 (LightRequest.available_stats() 결과)
    """
    issues: list[str] = []
    if len(resp.highlights) != 3:
        issues.append(f"count:{len(resp.highlights)}")
    seen: set[str] = set()
    for h in resp.highlights:
        if h.stat not in CANDIDATE_KEYS:
            issues.append(f"unknown_stat:{h.stat}")
        elif h.stat not in available:
            issues.append(f"unavailable_stat:{h.stat}")
        if h.stat in seen:
            issues.append(f"duplicate_stat:{h.stat}")
        seen.add(h.stat)
    return issues


# ---------------- heavy ----------------
class HeavyResponse(_StrictOut):
    chapter_id: str
    footnote: str = Field(min_length=1)
    # 10/20 백엔드 요청: 서버 대체 문장이면 true (백엔드가 캐시 제외·로그에 사용). LLM 문장이면 false
    fallback: bool = False


def parse_heavy_output(text: str, chapter_id: str) -> HeavyResponse:
    """(heavy-draft-v1, JSON 출력용) heavy 형식 검사 (v1.2 5.3절 "형식 오류" 4가지).

    유효한 JSON 아님 / 두 필드 중 하나라도 없음 / chapter_id 불일치 / footnote 빈 문자열·공백
    → OutputFormatError. v2 부터 서버는 process_heavy_output 을 쓴다.
    """
    try:
        data = _load_json(text, lenient=True)
    except json.JSONDecodeError as e:
        raise OutputFormatError(f"유효한 JSON이 아님: {e}") from e
    try:
        resp = HeavyResponse.model_validate(data)
    except ValidationError as e:
        first = e.errors()[0]
        raise OutputFormatError(f"키 구성 오류: {first['loc']} {first['msg']}") from e
    if resp.chapter_id != chapter_id:
        raise OutputFormatError(f"chapter_id 불일치: 요청 {chapter_id}, 출력 {resp.chapter_id}")
    return resp


# heavy 시스템 프롬프트에만 있는 문구 (출력에 있으면 베낀 것)
HEAVY_ECHO_MARKERS = (
    "당신은 테트리스", "어시스턴트입니다", "[규칙]", "[출력 형식]", "1-2문장", "입력과 같은 값",  # v1·v2
    "[용어]", "[해석]", "[예시]", "각주를 한국어로 씁니다", "값은 실제 입력과 다름", "챕터 아래에 붙는",  # v3
    "문장마다 한 번", "비교 기준이 없는 문장",  # v4
)
_CJK = re.compile(r"[぀-ヿ一-鿿]")  # 일본어 가나 + 한자(중국어)
# 문장 끝 = . ! ? 。 다음이 공백이거나 끝 (-62.87, TETR.IO 처럼 글자 사이의 점은 문장 끝이 아님)
_SENTENCE = re.compile(r".+?[.!?。](?=\s|$)")
_SNAKE = re.compile(r"(?<![A-Za-z0-9_*])[A-Za-z]+_[A-Za-z][A-Za-z_]*")  # weighted_app 같은 변수 이름 꼴
_BULLET = re.compile(r"^\s*[-*•]\s+", re.MULTILINE)  # -0.05 처럼 바로 숫자가 붙은 부호는 건드리지 않음
_HEADER = re.compile(r"^\s*(#+\s*)?([\"'`]?[A-Za-z_]+[\"'`]?\s*(챕터)?\s*(의)?\s*)?(각주|footnote)\s*([:：]\s*|\s+)", re.IGNORECASE)


def _data_keys(data: object) -> set[str]:
    """data 안의 모든 키 이름 (중첩 포함). 문장에 그대로 나오면 안 된다."""
    keys: set[str] = set()
    if isinstance(data, dict):
        for k, v in data.items():
            keys.add(k)
            keys |= _data_keys(v)
    elif isinstance(data, list):
        for v in data:
            keys |= _data_keys(v)
    return keys


# ---------- 내용 검사 (v3.2~, v4 는 31번 가이드라인 1.2·1.3·3.2절) ----------
_NUM = re.compile(r"\d+(?:\.\d+)?")
# 방향 단어. v4: 우열 서술어(앞섭니다/밀립니다)·성향 서술어(짙게/옅게, 큰/작은 편) 추가 (31번 1.3절)
_POS = ("높", "많", "강해", "강한", "강합", "강함", "강하", "우위", "오르", "오릅", "올라", "상승", "늘",
        "앞섭", "앞서", "짙", "큰 편", "큽니", "크고")
_NEG = ("낮", "적습", "적어", "적은", "약해", "약한", "약합", "약함", "약하", "떨어", "내리", "내립", "하락", "줄",
        "밀립", "밀려", "밀리", "뒤처", "열세", "옅", "작은 편", "작습", "작고")
# 방향 검사 대상: 문장 속 용어 → data 키 (긴 이름부터 찾는다: "Weighted APP" 안에 "APP" 가 있음)
_DIRECTION_TERMS = {
    # v4.4: 31번 1.8절 이름 (예전 이름은 normalize_names 가 먼저 바꾼다)
    "attack": [("보정 공격 효율", "delta_weighted_app"), ("블록당 공격력", "delta_app")],
    "defense": [("가비지 처리", "delta_cheese_index"), ("수비 비중", "delta_vs_apm")],
    "playstyle": [("Opener", "delta_opener"), ("Plonk", "delta_plonk"), ("Stride", "delta_stride"),
                  ("Infinite Downstacking", "delta_inf_ds")],
}
_BADGES = ("천적", "우세", "접전")
_MASKED = re.compile(r"[A-Za-z0-9_]*\*+[A-Za-z0-9_*]*[A-Za-z0-9_]|[A-Za-z0-9_]+\*+")  # 글자가 붙은 마스킹 닉네임

# 31번 1.2·1.3절 금지 표현
_RANK_WORDS = ("앞섭", "앞서", "밀립", "밀려", "우위", "열세")  # 우열 서술어 — 성향 지표에는 쓰지 않음
_OTHER_USERS = ("다른 유저", "다른 플레이어", "다른 사람", "평균적인", "비슷한 실력", "일반적인 유저", "전체 유저",
                "보통 수준", "드문", "평범", "흔한", "흔히", "대부분의 유저")  # v4.1: 분포를 문장에 섞지 않음 (1.3절)
# v4.1: 보통 범위 안의 값에 쓰면 과장 (31번 2.2절 "상대 대비 공격 효율에서 크게 앞섭니다(+0.01)" ✗)
# v4.3: 괄호 꼬리표를 명사형 그대로 옮긴 꼴 (v4.1~4.2 입력의 흔적, 10/16 실측 "옅음입니다", "앞섬하며")
_LABEL_LEAK = ("차이 작음", "변화 작음", "앞섬", "밀림", "옅음", "짙음", "작음입", "큼입")
# 정의 되풀이 ("…에서 …을 뺀 값입니다") — 규칙 "용어의 정의를 다시 설명하지 말 것"
_DEFINITION = ("뺀 값", "빼면", "빼는 값", "0과 1 사이")
# "-62.87 낮습니다"처럼 부호 붙은 숫자 + 높다/낮다 (이중 표현)
_SIGNED_COMPARE = re.compile(r"[-+−]\d[\d.]*\s*(점\s*수?\s*(가|로)?\s*|로\s*|만큼\s*)?(더\s*)?"
                             r"(낮|높|밀리|밀립|앞서|앞섭|내리|내립|오르|오릅)")   # v4.4: '-62.87 점수 낮습니다', '-0.05로 밀립니다'
_LABEL_COLON = re.compile(r"(차이|성향|비중|효율|공격력|승률|변화)\s*:")      # v4.4 실측: '수비 비중 차이: …' (입력 줄 베낌)
_COMEBACK_TAIL_OK = re.compile(r"역전승 경향|역전패 경향|뒤졌던|뒤집|앞섰던|내준|내줬")
_INTENSIFIERS = ("크게", "뚜렷", "두드러", "매우", "훨씬", "압도", "확연", "현저")
_RECENT_FORM = re.compile(r"연승|연패|최근\s*\d+\s*(경기|판|게임)")
_EST_TR = ("Est", "추정 TR", "statrank")
_SCORE_GAP = re.compile(r"\d+\s*(판|점|세트|라운드)\s*(이상\s*)?(차|뒤|앞)")  # "2판 이상 뒤진" (31번 1.2-4)
_TYPED = ("플레이어", "공격형", "수비형", "유형의")  # 사람을 유형으로 규정 (C3)
# strength_split: "상대보다", "상대 대비" 같은 상대 비교 (31번 1.2-7). "강한 상대 구간보다"는 허용
# 상대 비교 챕터의 비교 기준: 1.3절 5개 표현과 "상대보다/상대와/상대에 비해" 꼴. "상대로 밀립니다"는 아님
_BASIS = re.compile(r"상대들?\s*(보다|대비|와|에\s*비해|평균)")
_VS_OPPONENT = re.compile(r"상대(보다|\s*대비|에\s*비해|들|와\s*비교|와\s*견|\s*평균)")


def _numbers(values) -> set[float]:
    """입력 값에서 문장에 나와도 되는 숫자들 (절대값과 반올림). v4: ×100 백분율은 뺌 (%·%p 금지, D2)."""
    out: set[float] = set()
    for v in values:
        a = abs(float(v))
        for c in (a, round(a), round(a, 1), round(a, 2), round(a, 3)):
            out.add(round(c, 4))
    return out


def _numeric_values(data: object) -> list[float]:
    vals: list[float] = []
    if isinstance(data, dict):
        for v in data.values():
            vals += _numeric_values(v)
    elif isinstance(data, list):
        for v in data:
            vals += _numeric_values(v)
    elif isinstance(data, (int, float)) and not isinstance(data, bool):
        vals.append(data)
    return vals


def _check_numbers(text: str, allowed: set[float], where: str) -> None:
    for m in _NUM.findall(text):
        if round(float(m), 4) not in allowed:
            raise OutputFormatError(f"입력에 없는 숫자{where}: {m}")


def _first_direction(segment: str) -> int:
    """구간에서 처음 나오는 방향 단어: +1 높음 쪽, -1 낮음 쪽, 0 없음."""
    best, sign = len(segment) + 1, 0
    for words, s in ((_POS, 1), (_NEG, -1)):
        for w in words:
            i = segment.find(w)
            if 0 <= i < best:
                best, sign = i, s
    return sign


def _sign(value: object) -> int | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    return 1 if value > 0 else -1 if value < 0 else 0


def _sentences(footnote: str) -> list[str]:
    return [s.strip() for s in _SENTENCE.findall(footnote)] or [footnote]


def _term_spots(text: str, chapter_id: str) -> tuple[str, list[tuple[int, str]]]:
    """문장 속 용어 위치. 긴 이름부터 찾고 지워서 "APP" 가 "Weighted APP" 안에서 다시 잡히지 않게."""
    masked = text
    spots: list[tuple[int, str]] = []
    for term, key in sorted(_DIRECTION_TERMS.get(chapter_id, []), key=lambda t: -len(t[0])):
        start = 0
        while (i := masked.find(term, start)) >= 0:
            spots.append((i, key))
            masked = masked[:i] + "#" * len(term) + masked[i + len(term):]
            start = i + len(term)
    return masked, sorted(spots)


def _check_direction(footnote: str, chapter_id: str, data: dict) -> None:
    if chapter_id not in _DIRECTION_TERMS:
        return
    for sentence in _sentences(footnote):
        masked, spots = _term_spots(sentence, chapter_id)
        if not spots:
            continue
        # v4: "A와 B 모두 …" 로 묶었으면 두 값의 방향이 같아야 하고, 서술어도 그 방향이어야 한다
        if "모두" in sentence:
            keys = {k for _, k in spots}
            signs = {_sign(data.get(k)) for k in keys} - {None}
            d = _first_direction(masked[spots[0][0]:])
            # "모두 차이가 크지 않습니다"처럼 방향 서술어가 없으면 부호가 달라도 된다
            if d and len(keys) >= 2 and len(signs) > 1:
                raise OutputFormatError(f"방향이 다른 두 지표를 '모두'로 묶음: {sorted(keys)}")
            if len(signs) == 1 and d and (s := signs.pop()) and d != s:
                raise OutputFormatError(f"'모두' 문장의 방향이 값과 반대: {sorted(keys)}")
        for n, (i, key) in enumerate(spots):
            end = spots[n + 1][0] if n + 1 < len(spots) else len(masked)
            value = data.get(key)
            if not _sign(value):
                continue
            d = _first_direction(masked[i:end])
            if d and d != _sign(value):
                raise OutputFormatError(f"부호와 반대로 해석: {key}={value}")


def _check_intensity(footnote: str, chapter_id: str, data: dict) -> None:
    """v4.1: 보통 범위(31번 2.3절) 안의 값에 '크게·뚜렷' 같은 강조를 쓰면 과장 (2.2절)."""
    from app.prompt import heavy_zone, strength_level
    if not any(w in footnote for w in _INTENSIFIERS):
        return
    if chapter_id == "strength_split":
        v = data.get("strength_split")
        if _sign(v) is not None and strength_level(v) != "low":
            raise OutputFormatError(f"강조할 만큼 큰 차이가 아님(과장): strength_split={v}")
        return
    if chapter_id == "session_vs_slope":
        v = data.get("session_vs_slope")
        if _sign(v) and heavy_zone("session_vs_slope", v) == "mid":
            raise OutputFormatError(f"강조할 만큼 큰 변화가 아님(과장): session_vs_slope={v}")
        return
    for sentence in _sentences(footnote):
        hit = [w for w in _INTENSIFIERS if w in sentence]
        if not hit:
            continue
        _, spots = _term_spots(sentence, chapter_id)
        keys = {k for _, k in spots if _sign(data.get(k))}
        if keys and all(heavy_zone(k, data[k]) == "mid" for k in keys):
            raise OutputFormatError(f"강조할 만큼 큰 차이가 아님(과장): {sorted(keys)} {hit}")


def _check_comeback_direction(footnote: str, data: dict) -> None:
    """역전: "역전승률이 역전 허용률보다 높다/낮다"의 방향 (v4 — light 측정에서 역전 방향 오류가 가장 많았음)."""
    a, b = data.get("comeback_rate"), data.get("comeback_rate_against")
    if _sign(a) is None or _sign(b) is None:
        return
    expected = 1 if a > b else -1 if a < b else 0
    for sentence in _sentences(footnote):
        i, j, k = sentence.find("역전승률"), sentence.find("역전 허용률"), sentence.find("보다")
        if i < 0 or j < 0 or k < 0:
            continue
        d = _first_direction(sentence[k:])
        want = expected if i < j else -expected  # 역전 허용률을 주어로 쓰면 방향이 뒤집힘
        if d and want and d != want:
            raise OutputFormatError(f"역전승률·역전 허용률 비교 방향이 반대: {a} vs {b}")
        if d and not want:
            raise OutputFormatError("두 비율이 같은데 높다/낮다로 씀")


def _check_compare_basis(footnote: str, chapter_id: str) -> None:
    """31번 1.2-7·1.3절: 비교 기준."""
    if chapter_id in ("playstyle", "attack", "defense"):
        for sentence in _sentences(footnote):
            # 지표 이름이나 방향 서술어가 있는 문장은 비교 기준이 있어야 한다.
            # 5개 표현과 "상대보다" 모두 "상대"를 포함. ("공격 효율에서 앞섭니다." ✗ — 31번 1.3절 예시)
            if "상대적" in sentence:  # "상대적으로 앞섭니다" — 누구보다인지 없음, 허용 표현 5개에 없음 (10/16 실측)
                raise OutputFormatError(f"'상대적으로'는 비교 기준이 아님 (31번 1.3절): {sentence}")
            _, spots = _term_spots(sentence, chapter_id)
            if (spots or _first_direction(sentence)) and not _BASIS.search(sentence):
                raise OutputFormatError(f"비교 기준(상대)이 빠진 문장 (C1 i): {sentence}")
    elif chapter_id in ("tr_trend", "session_vs_slope") and "상대" in footnote:
        raise OutputFormatError("상대와 비교하지 않는 지표에 '상대'를 씀")
    elif chapter_id == "strength_split" and _VS_OPPONENT.search(footnote):
        raise OutputFormatError("상대 강도별 승률에 '상대보다/상대 대비'를 씀")


def _check_playstyle_compare(footnote: str) -> None:
    """C2: 플레이스타일 4개끼리 비교·순위 금지."""
    names = ("Opener", "Plonk", "Stride", "Infinite Downstacking")
    for sentence in _sentences(footnote):
        if "가장" in sentence:
            raise OutputFormatError("플레이스타일 순위를 매김 (C2)")
        rest = re.sub(r"상대[^\s]*\s*(평균\s*)?(보다|대비|에\s*비해)", "", sentence)
        if "보다" in rest and sum(n in rest for n in names) >= 2:
            raise OutputFormatError("플레이스타일 성향끼리 비교함 (C2)")


def _check_rivals(footnote: str, data: dict) -> None:
    """라이벌: 닉네임마다 그 뒤 구간의 배지·숫자가 그 상대의 값과 맞는지. v4: 승률 숫자는 받지 않음."""
    from app.prompt import _rival_badge
    info: dict[str, tuple[str, set[float]]] = {}
    for r in data.get("rivals") or []:
        nick = r.get("nickname_masked")
        if not nick:
            continue
        m, w, lo = r.get("matches") or 0, r.get("wins") or 0, r.get("losses") or 0
        rate = w / m if m else 0.0
        info[nick] = (_rival_badge(rate), _numbers([m, w, lo]) | {5.0})  # 5 = "5경기 이상"
    for token in _MASKED.findall(footnote):
        if not any(token in nick for nick in info):
            raise OutputFormatError(f"입력에 없는 닉네임: {token}")
    spots = sorted((i, nick) for nick in info
                   for i in [m.start() for m in re.finditer(re.escape(nick), footnote)])
    head = footnote[: spots[0][0]] if spots else footnote
    present = {b for b, _ in info.values()}
    for b in _BADGES:
        if b in head and b not in present:
            raise OutputFormatError(f"입력에 없는 배지: {b}")
    _check_numbers(head, set().union(*(a for _, a in info.values())) if info else {5.0}, "")
    for n, (i, nick) in enumerate(spots):
        end = spots[n + 1][0] if n + 1 < len(spots) else len(footnote)
        seg = footnote[i + len(nick): end]
        badge, allowed = info[nick]
        for b in _BADGES:
            if b in seg and b != badge:
                raise OutputFormatError(f"{nick} 배지 불일치: {b} (실제 {badge})")
        _check_numbers(seg, allowed, f"({nick})")


# 값이 하나인 챕터: 값이 음수면 아래 POS 단어, 양수면 NEG 단어가 문장 어디에든 나오면 거절 (v3.4)
_SINGLE_VALUE = {"tr_trend": "tr_trend_delta", "strength_split": "strength_split", "session_vs_slope": "session_vs_slope"}
_SINGLE_POS = ("상승", "높", "오르", "오릅", "올라", "강해", "늘")
_SINGLE_NEG = ("하락", "낮", "내리", "내립", "내려", "처지", "처져", "떨어", "무너")
# [해석]의 부호 규칙을 되풀이하는 말 (값의 뜻이 아니라 규칙 설명)
_RULE_RESTATE = ("양수면", "음수면", "양수인 경우", "음수인 경우", "양수일 경우", "음수일 경우", "5등분", "양수로 해석", "음수 폭", "양수이며", "음수이며")
_RIVAL_DESCRIBE = ("선수", "전략", "투수", "성향")


def _strip_strength_percent(footnote: str, data: dict) -> str:
    """v4.2: 상대 강도별 승률 값과 같은 숫자에 % 를 붙인 꼴("0.21% 낮습니다", "21%p 낮습니다")은 숫자째 지운다.
    %·%p 는 금지(31번 1.2-1, D2)라 고칠 수 없고, 방향 말("낮습니다")은 맞으므로 숫자만 빼면 옳은 문장이 된다.
    값과 다른 숫자의 % 는 그대로 두어 뒤의 검사에서 거절된다. (10/16 실측 8/20 이 이 경우)"""
    v = data.get("strength_split")
    if _sign(v) is None:
        return footnote
    a = abs(v)
    nums = {f"{a:g}", f"{round(a * 100):g}", f"{round(a * 100, 1):g}"}
    pat = "|".join(re.escape(n) for n in sorted(nums, key=len, reverse=True))
    out = re.sub(rf"\s*(?<![\d.])(?:{pat})\s*%p?(?=\s|\S)", "", footnote)
    return re.sub(r"\s{2,}", " ", out).replace(" .", ".")


def _check_single_direction(footnote: str, chapter_id: str, data: dict) -> None:
    key = _SINGLE_VALUE.get(chapter_id)
    value = data.get(key) if key else None
    if not _sign(value):
        return
    wrong = _SINGLE_POS if value < 0 else _SINGLE_NEG
    hit = [w for w in wrong if w in footnote]
    if hit:
        raise OutputFormatError(f"부호와 반대 방향의 말: {key}={value} {hit}")


def _check_heavy_content(footnote: str, chapter_id: str, data: dict) -> None:
    from app.prompt import HEAVY_CHAPTERS as CH
    # --- 31번 1.2절 금지 규칙 (모든 챕터) ---
    if "%" in footnote:
        raise OutputFormatError("%·%p 를 씀 (31번 1.2-1, D2 금지)")
    typed = [w for w in _TYPED if w in footnote]
    if typed:
        raise OutputFormatError(f"사람을 한 유형으로 규정함 (C3): {typed}")
    others = [w for w in _OTHER_USERS if w in footnote]
    if others:
        raise OutputFormatError(f"다른 유저와 비교함 (31번 1.3절): {others}")
    if _RECENT_FORM.search(footnote):
        raise OutputFormatError("연승·연패·최근 N경기를 씀 (31번 1.2-8)")
    if any(w in footnote for w in _EST_TR):
        raise OutputFormatError("Est.TR·statrank 언급 (31번 1.2-5)")
    if _SCORE_GAP.search(footnote):
        raise OutputFormatError("점수 차를 단정함 (31번 1.2-4)")
    ch = CH.get(chapter_id, {})
    for line in (ch.get("rules", "") + "\n" + ch.get("terms", "")).split("\n"):
        line = line.strip().lstrip("- ").strip()
        if len(line) >= 25 and line[:25] in footnote:
            raise OutputFormatError("[해석]·[용어] 문장을 베낌")
    leak = [w for w in _LABEL_LEAK if w in footnote]
    if leak:
        raise OutputFormatError(f"입력 꼬리표를 명사형 그대로 옮김: {leak}")
    defined = [w for w in _DEFINITION if w in footnote]
    if defined:
        raise OutputFormatError(f"지표 정의를 되풀이함: {defined}")
    if _SIGNED_COMPARE.search(footnote):
        raise OutputFormatError("부호 붙은 숫자에 높다/낮다를 붙임 (예: -62.87 낮습니다)")
    if chapter_id != "rivals" and _LABEL_COLON.search(footnote):
        raise OutputFormatError("입력 줄을 '이름: 값' 꼴로 옮김")
    if "(상대보다" in footnote or "(상대와" in footnote:
        raise OutputFormatError("입력 괄호를 그대로 옮김")
    if chapter_id == "tr_trend":
        if re.search(r"[-+−]\d", footnote):
            raise OutputFormatError("TR 추이에 부호 붙은 숫자를 씀 (v4.4 실측: '-62.87 점수 차이')")
        if _sign(data.get("tr_trend_delta")) is not None and "TR" not in footnote and "대전 점수" not in footnote:
            raise OutputFormatError("TR 추이인데 TR을 말하지 않음 (v4.4 실측: '최근 구간의 공격력을 …')")
    restate = [w for w in _RULE_RESTATE if w in footnote]
    if restate:
        raise OutputFormatError(f"[해석] 규칙을 되풀이함: {restate}")
    if chapter_id == "rivals":
        described = [w for w in _RIVAL_DESCRIBE if w in footnote]
        if described:
            raise OutputFormatError(f"상대를 지어내 묘사함: {described}")
        if "승률" in footnote:
            raise OutputFormatError("라이벌 승률을 씀 (31번 3.2절 승률 계산 금지)")
        _check_rivals(footnote, data)
        return
    # --- 챕터별 ---
    _check_compare_basis(footnote, chapter_id)
    if chapter_id in ("playstyle", "defense"):
        rank = [w for w in _RANK_WORDS if w in footnote]
        if rank:
            raise OutputFormatError(f"성향 지표에 우열 서술어를 씀 (31번 1.3절): {rank}")
    if chapter_id == "playstyle":
        _check_playstyle_compare(footnote)
    if chapter_id == "strength_split" and re.search(r"약한 상대[^.]*?강한 상대[^.]*?보다", footnote):
        # 주어를 바꾸면 방향 검사가 거꾸로 된다 → 강한 구간을 주어로 쓴 문장만 받음
        raise OutputFormatError("약한 상대 구간을 주어로 비교함")
    _check_numbers(footnote, _numbers(_numeric_values(data)), "")
    _check_direction(footnote, chapter_id, data)
    _check_single_direction(footnote, chapter_id, data)
    _check_intensity(footnote, chapter_id, data)
    if chapter_id == "comeback_rate":
        _check_comeback_direction(footnote, data)


def process_heavy_output(text: str, chapter_id: str, data: dict) -> HeavyResponse:
    """모델이 쓴 각주 문장을 다듬고 검사해 응답 {chapter_id, footnote} 를 만든다.

    다듬기: 앞뒤 공백·따옴표, "각주:" 같은 머리말을 지우고, 첫 문단에서 완성된 문장 최대 2개만 쓴다
            (v1.2 5.3절 footnote 1–2문장. 출력 한도에서 잘린 뒤쪽 미완성 문장은 버린다).
    형식·내용 오류(→ 서버가 다시 생성, 끝내 실패하면 대체 문장 또는 502):
      완성된 문장 없음 / 프롬프트 베낌 / 중국어·일본어 / data 키 이름 노출 / 31번 가이드라인 금지 규칙
    예전 형식(JSON {"chapter_id","footnote"})으로 답해도 footnote 를 꺼내 같은 검사를 한다.
    """
    raw = text.strip()
    if raw.startswith("{"):
        try:
            obj = _load_json(raw, lenient=True)
            if isinstance(obj, dict) and isinstance(obj.get("footnote"), str):
                raw = obj["footnote"]
        except json.JSONDecodeError:
            pass
    first_para = raw.strip().split("\n\n")[0]
    first_para = _BULLET.sub("", first_para).replace("\n", " ")  # 줄 앞의 "- ", "* " 목록 기호 (v3.1)
    first_para = _HEADER.sub("", first_para).strip().strip("\"'`“”‘’ ").strip()
    first_para = re.sub(r"^[\"'`]?[A-Za-z_]+[\"'`]\s*", "", first_para)  # 앞에 남은 "attack" 같은 꼬리표
    sentences = [s.strip() for s in _SENTENCE.findall(first_para) if s.strip()]
    if not sentences:
        raise OutputFormatError("완성된 문장이 없음")
    footnote = " ".join(sentences[:2]).strip("\"'`“”‘’ ")
    if chapter_id != "rivals":   # v4.4: 예전·영어 지표 이름 → 31번 1.8절 이름 (닉네임은 건드리지 않음)
        footnote = normalize_names(footnote)
    if chapter_id == "strength_split":
        footnote = _strip_strength_percent(footnote, data)
    if chapter_id == "comeback_rate" and len(sentences) > 1 and not _COMEBACK_TAIL_OK.search(sentences[1]):
        # v4.4 실측: 둘째 문장이 '역전 팽행', '역전 패스 기회'처럼 뜻이 없는 말 → 첫 문장(비교)만 남긴다
        footnote = normalize_names(sentences[0].strip("\"'`“”‘’ "))

    echo = [m for m in HEAVY_ECHO_MARKERS if m in footnote]
    if echo:
        raise OutputFormatError(f"시스템 프롬프트를 베낀 출력: {echo}")
    # (v3.3~v4 의 "숫자가 든 예시 베낌" 검사는 v4.1 에서 뺌 — 예시가 입력과 같은 방향·강도의 숫자 없는 문장이라
    #  베껴도 틀리지 않고, 숫자를 베끼면 아래 숫자 검사에 걸린다)
    if _CJK.search(footnote):
        raise OutputFormatError("중국어·일본어 문자가 섞임")
    leaked = sorted(k for k in _data_keys(data) | {"chapter_id", "footnote"} if k in footnote)
    if not leaked and chapter_id != "rivals":  # 라이벌은 마스킹 닉네임에 _ 가 들어갈 수 있어 제외
        leaked = _SNAKE.findall(footnote)
    if leaked:
        raise OutputFormatError(f"지표 키 이름이 문장에 나옴: {leaked}")
    _check_heavy_content(footnote, chapter_id, data)
    return HeavyResponse(chapter_id=chapter_id, footnote=footnote)
