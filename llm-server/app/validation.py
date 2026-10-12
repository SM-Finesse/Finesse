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

from app.prompt import HEAVY_CHAPTERS
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
)
_CJK = re.compile(r"[\u3040-\u30ff\u4e00-\u9fff]")  # 일본어 가나 + 한자(중국어)
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


# ---------- v3.2 내용 검사 ----------
_NUM = re.compile(r"\d+(?:\.\d+)?")
_DECIMAL_PERCENT = re.compile(r"(?<![\d.])0\.\d+\s*%")  # "0.21%" — 0~1 비율에 % 를 붙인 꼴
_POS = ("높", "많", "강해", "강한", "강합", "강함", "강하", "우위", "오르", "오릅", "올라", "상승", "늘")
_NEG = ("낮", "적습", "적어", "적은", "약해", "약한", "약합", "약함", "약하", "떨어", "내리", "내립", "하락", "줄", "밀립", "뒤처")
# 방향 검사 대상: 문장 속 용어 → data 키 (긴 이름 먼저 찾는다: "Weighted APP" 안에 "APP" 가 있음)
_DIRECTION_TERMS = {
    "attack": [("Weighted APP", "delta_weighted_app"), ("APP", "delta_app")],
    "defense": [("VS/APM", "delta_vs_apm"), ("Cheese Index", "delta_cheese_index")],
    "playstyle": [("Opener", "delta_opener"), ("Plonk", "delta_plonk"), ("Stride", "delta_stride"), ("Inf DS", "delta_inf_ds")],
}
_BADGES = ("천적", "우세", "접전")
_MASKED = re.compile(r"[A-Za-z0-9_]*\*+[A-Za-z0-9_*]*[A-Za-z0-9_]|[A-Za-z0-9_]+\*+")  # 글자가 붙은 마스킹 닉네임


def _numbers(values) -> set[float]:
    """입력 값에서 문장에 나와도 되는 숫자들 (절대값, 반올림, ×100 백분율)."""
    out: set[float] = set()
    for v in values:
        a = abs(float(v))
        for c in (a, round(a), round(a, 1), round(a, 2), a * 100, round(a * 100), round(a * 100, 1)):
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


def _check_direction(footnote: str, chapter_id: str, data: dict) -> None:
    terms = _DIRECTION_TERMS.get(chapter_id)
    if not terms:
        return
    masked = footnote
    spots: list[tuple[int, str]] = []
    for term, key in terms:  # 긴 이름부터 찾고 지워서 "APP" 가 "Weighted APP" 안에서 다시 잡히지 않게
        start = 0
        while (i := masked.find(term, start)) >= 0:
            spots.append((i, key))
            masked = masked[:i] + "#" * len(term) + masked[i + len(term):]
            start = i + len(term)
    spots.sort()
    for n, (i, key) in enumerate(spots):
        end = spots[n + 1][0] if n + 1 < len(spots) else len(masked)
        seg = masked[i:end]
        cut = re.search(r"[.!?](?=\s|$)", seg)
        seg = seg[: cut.end()] if cut else seg
        value = data.get(key)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or value == 0:
            continue
        d = _first_direction(seg)
        if d and d != (1 if value > 0 else -1):
            raise OutputFormatError(f"부호와 반대로 해석: {key}={value}")


def _check_rivals(footnote: str, data: dict) -> None:
    """라이벌: 닉네임마다 그 뒤 구간의 배지·숫자가 그 상대의 값과 맞는지."""
    from app.prompt import _rival_badge
    info: dict[str, tuple[str, set[float]]] = {}
    for r in data.get("rivals") or []:
        nick = r.get("nickname_masked")
        if not nick:
            continue
        m, w, lo = r.get("matches") or 0, r.get("wins") or 0, r.get("losses") or 0
        rate = w / m if m else 0.0
        info[nick] = (_rival_badge(rate), _numbers([m, w, lo, rate]) | {5.0})  # 5 = "5경기 이상"
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


def _strength_percent_point(footnote: str, data: dict) -> str:
    """상대 강도: 값과 같은 숫자에 붙은 "21%" 는 "21%p" 로 고친다 (v3.5, 10/12 실측에서 4/10 이 이 경우).
    값과 다른 숫자의 % 는 그대로 두어 뒤의 검사에서 거절된다."""
    v = data.get("strength_split")
    if not isinstance(v, (int, float)) or isinstance(v, bool):
        return footnote
    p = round(abs(v) * 100)
    return re.sub(rf"(?<![\d.]){p}\s*%(?!p)", f"{p}%p", footnote)


def _check_single_direction(footnote: str, chapter_id: str, data: dict) -> None:
    key = _SINGLE_VALUE.get(chapter_id)
    value = data.get(key) if key else None
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value == 0:
        return
    wrong = _SINGLE_POS if value < 0 else _SINGLE_NEG
    hit = [w for w in wrong if w in footnote]
    if hit:
        raise OutputFormatError(f"부호와 반대 방향의 말: {key}={value} {hit}")


def _check_heavy_content(footnote: str, chapter_id: str, data: dict) -> None:
    from app.prompt import HEAVY_CHAPTERS as CH
    if _DECIMAL_PERCENT.search(footnote):
        raise OutputFormatError("0~1 비율에 % 를 붙임")
    ch = CH.get(chapter_id, {})
    for line in (ch.get("rules", "") + "\n" + ch.get("terms", "")).split("\n"):
        line = line.strip().lstrip("- ").strip()
        if len(line) >= 25 and line[:25] in footnote:
            raise OutputFormatError("[해석]·[용어] 문장을 베낌")
    if chapter_id == "strength_split" and re.search(r"약한 상대[^.]*?강한 상대[^.]*?보다", footnote):
        # "약한 구간이 강한 구간보다 높다" 처럼 주어를 바꾸면 방향 검사가 거꾸로 된다 → 강한 구간을 주어로 쓴 문장만 받음
        raise OutputFormatError("약한 상대 구간을 주어로 비교함")
    if chapter_id == "strength_split" and re.search(r"\d\s*%(?!p)", footnote):
        raise OutputFormatError("승률 차는 %p 로 써야 함")  # 21%p 는 되고 21% 는 안 됨 (비율의 차이)
    typed = [w for w in ("플레이어", "공격형", "수비형") if w in footnote]
    if typed:
        raise OutputFormatError(f"플레이어를 한 유형으로 규정함: {typed}")  # 공통 규칙 (v3.6)
    restate = [w for w in _RULE_RESTATE if w in footnote]
    if restate:
        raise OutputFormatError(f"[해석] 규칙을 되풀이함: {restate}")
    if chapter_id == "rivals":
        described = [w for w in _RIVAL_DESCRIBE if w in footnote]
        if described:
            raise OutputFormatError(f"상대를 지어내 묘사함: {described}")
        _check_rivals(footnote, data)
        return
    _check_numbers(footnote, _numbers(_numeric_values(data)), "")
    _check_direction(footnote, chapter_id, data)
    _check_single_direction(footnote, chapter_id, data)


def process_heavy_output(text: str, chapter_id: str, data: dict) -> HeavyResponse:
    """heavy-draft-v2: 모델이 쓴 각주 문장을 다듬고 검사해 응답 {chapter_id, footnote} 를 만든다.

    다듬기: 앞뒤 공백·따옴표, "각주:" 같은 머리말을 지우고, 첫 문단에서 완성된 문장 최대 2개만 쓴다
            (v1.2 5.3절 footnote 1–2문장. 출력 한도에서 잘린 뒤쪽 미완성 문장은 버린다).
    형식 오류(→ 502, 백엔드가 그 챕터만 재요청):
      완성된 문장 없음 / 프롬프트 베낌 / 중국어·일본어 / data 키 이름(delta_app 등)이 문장에 그대로 나옴
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
    if chapter_id == "strength_split":
        footnote = _strength_percent_point(footnote, data)

    echo = [m for m in HEAVY_ECHO_MARKERS if m in footnote]
    if echo:
        raise OutputFormatError(f"시스템 프롬프트를 베낀 출력: {echo}")
    from app.prompt import pick_heavy_example
    example = pick_heavy_example(chapter_id, data) if chapter_id in HEAVY_CHAPTERS else ""
    example_first = _SENTENCE.findall(example)[0] if _SENTENCE.findall(example) else ""
    # v3.4: 숫자가 없는 상황별 예시는 입력 방향과 같으므로 베껴도 틀리지 않는다 → 숫자가 든 예시만 검사
    if example_first and re.search(r"\d", example_first) and example_first.strip() in footnote:
        raise OutputFormatError("프롬프트의 예시 문장을 그대로 베낌")
    if _CJK.search(footnote):
        raise OutputFormatError("중국어·일본어 문자가 섞임")
    leaked = sorted(k for k in _data_keys(data) | {"chapter_id", "footnote"} if k in footnote)
    if not leaked and chapter_id != "rivals":  # 라이벌은 마스킹 닉네임에 _ 가 들어갈 수 있어 제외
        leaked = _SNAKE.findall(footnote)
    if leaked:
        raise OutputFormatError(f"지표 키 이름이 문장에 나옴: {leaked}")
    _check_heavy_content(footnote, chapter_id, data)
    return HeavyResponse(chapter_id=chapter_id, footnote=footnote)
