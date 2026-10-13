"""라이트 뷰 v3 (light-v3-lines, 2026-10-13).

v2(light-baseline-v2-trsum)의 문제 (25번 실측, 음수·반대 방향 샘플 각 20회)
  - 서버 통과 5/20, 11/20. 통과 중 세 문장·총평이 모두 맞는 것 0, 2.
  - 시스템 프롬프트 첫 문장("당신은 테트리스 … 어시스턴트입니다")을 총평에 베낌 (14/40).
  - JSON 을 두 개로 나눠 쓰거나 잘림, 11개 밖 stat(playstyle_relative 등).
  - 입력 순서대로 앞의 3개를 고름 (40회 중 39회가 delta_opener 부터, opener 가 가장 작은 값일 때도).
  - 부호 오해석, "투수율"·"플로킹" 같은 다른 분야 말, 없는 숫자, 영어 섞임.

v3 에서 바꾼 것 (헤비뷰 v3.x 에서 효과가 확인된 방식)
  1. 후보를 번호 + 문서 용어 이름 + 값 + 방향(서버가 계산)으로 보여 준다. 영문 키를 보여 주지 않는다.
  2. 모델은 "번호 | 문장" 3줄 + "총평 | 문장" 1줄만 쓴다. stat 키와 JSON 은 서버가 조립한다.
  3. 시스템 프롬프트에서 "당신은 … 어시스턴트입니다" 문장을 뺀다 (베낌의 원인).
  4. 서버 내용 검사: 없는 숫자, 방향 반대, 영어·중국어, 프롬프트 베낌, 같은 번호 반복.
     틀린 하이라이트만 빼고 나머지를 보낸다 (백엔드는 1~2개도 받고 재요청으로 채움 — 유저 분석 페이지 4.2).
     총평이 틀리면 고른 하이라이트의 방향 문구로 서버가 총평을 만든다(선택은 하지 않음).
  선택은 계속 모델이 한다 (하이라이트 지표 설계: 백엔드 결정론적 사전 선별 기각).

PM 확인 전이라 기본값 off 인 선택 보조 (환경변수로 켜서 측정만)
  5. LLM_LIGHT_SIZE_HINT=on : 후보마다 "보통 범위 대비 차이 작음/보통/큼" 표시.
     보통 범위 = v1.2 5.1절 / 05 공식 해설 8절. 문서상 용도는 학습 더미 분포 기준이라 프롬프트 사용은 새 용도.
     작음/보통/큼 경계(보통 범위 절반 폭의 0.2·0.6배)는 이 파일의 제안값이며 문서 근거 없음.
     strength_split 은 문서에 보통 범위가 없어 표시하지 않는다.
     Weighted APP 보통 범위(-0.3~+0.3)는 문서 예시 값(1.4)과 맞지 않아 확인 필요.
  6. LLM_LIGHT_SHUFFLE=on : 후보 순서를 요청마다 섞는다 (순서대로 고르는 버릇 방지).
  ※ 5·6 은 아래 A/B/C 의 라벨·정렬로 대체된다. 이 v3(라벨 없음)는 비교 기준으로만 남긴다 (LLM_LIGHT_MODE=v3).

light-v4 (2026-10-13, 하이라이트 선정 규칙 반영) — LLM_LIGHT_MODE 로 고른다. 실행 흐름은 app/light_pipeline.py.
  공통: 후보를 서버가 두드러진 순서로 정렬하고 그룹·방향·라벨("낮은 편, 많이 벗어남")만 준다.
        값·범위 숫자는 주지 않는다 → 문장에 숫자가 나오면 모두 지어낸 숫자로 본다.
        모델이 고른 뒤 규칙 위반 선택을 뺀다 (app/light_rules.py, 사후 검사).
  A (1회) : 선정 + 문장 3 + 총평을 한 번에, "번호 | 문장" 줄 형식 (v3 형식 유지)
  B (2회) : ① 선정(번호 3개) ② 고른 3개의 문장 + 총평
  C (5회) : ① 선정 ② 지표 1개 = 문장 1개 × 3 (헤비 v3.6 방식: 방향·해석·같은 방향 예문) ③ 총평
"""
import os
import random
import re
from dataclasses import dataclass, field

from app.light_rules import LIGHT_GROUPS, RangeConfig, Standing, apply_rules, check_pick, load_ranges, rank
from app.schemas import LightRequest

LIGHT_V3_VERSION = "light-v3-lines"

# 후보 키 → (화면 이름, 뜻) — 04 지표 명세 8.1·9절, 데이터명세서 2절, 05 공식 해설 6·8절
LIGHT_TERMS: dict[str, tuple[str, str]] = {
    "delta_opener": ("Opener 성향 차이", "초반 공격 위주 성향"),
    "delta_plonk": ("Plonk 성향 차이", "효율·가비지 활용 위주 성향"),
    "delta_stride": ("Stride 성향 차이", "속도 위주 성향"),
    "delta_inf_ds": ("Inf DS 성향 차이", "다운스택(수비) 위주 성향"),
    "delta_app": ("APP 차이", "블록 1개당 공격량"),
    "delta_weighted_app": ("Weighted APP 차이", "Cheese Index로 보정한 공격 효율"),
    "delta_vs_apm": ("VS/APM 차이", "공격 대비 수비 비율"),
    "delta_cheese_index": ("Cheese Index 차이", "가비지 처리 성향"),
    "strength_split": ("상대 강도별 승률 차", "가장 강한 상대 구간 승률 − 가장 약한 상대 구간 승률"),
    "delta_comeback": ("역전 경향 차이", "역전승률 − 역전 허용률"),
    "session_vs_slope": ("라운드별 VS 기울기", "한 매치 안에서 라운드가 진행될 때 VS 변화"),
}

# (양수, 음수, 0) 방향 문구 — 04 지표 명세 9절 부호 규칙
LIGHT_DIRECTIONS: dict[str, tuple[str, str, str]] = {
    **{k: ("상대보다 강함", "상대보다 약함", "상대와 같음")
       for k in ("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds")},
    "delta_app": ("상대보다 공격 효율이 높음", "상대보다 공격 효율이 낮음", "상대와 같음"),
    "delta_weighted_app": ("보정 후에도 상대보다 공격 효율이 높음", "보정 후 상대보다 공격 효율이 낮음", "상대와 같음"),
    "delta_vs_apm": ("상대보다 수비 비중이 큼", "상대보다 수비 비중이 작음", "상대와 같음"),
    "delta_cheese_index": ("상대보다 가비지 처리 성향이 강함", "상대보다 가비지 처리 성향이 약함", "상대와 같음"),
    "strength_split": ("강한 상대에게 오히려 승률이 높음", "강한 상대를 만나면 승률이 떨어짐", "상대 강도와 상관없이 고름"),
    "delta_comeback": ("불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강함",
                       "유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강함", "두 경향이 비슷함"),
    "session_vs_slope": ("라운드가 진행될수록 강해짐", "라운드가 진행될수록 처짐", "뚜렷한 추세 없음"),
}

# 보통 범위의 절반 폭 (v1.2 5.1절, 05 공식 해설 8절). strength_split 은 문서에 범위 없음.
LIGHT_HALF_RANGE: dict[str, float] = {
    "delta_opener": 0.5, "delta_plonk": 0.5, "delta_stride": 0.5, "delta_inf_ds": 0.5,
    "delta_app": 0.3, "delta_weighted_app": 0.3, "delta_vs_apm": 0.5,
    "delta_cheese_index": 30.0, "delta_comeback": 0.5, "session_vs_slope": 20.0,
}

LIGHT_V3_DECODING = {"temperature": 0.4, "top_p": 0.9, "max_tokens": 377}

LIGHT_V3_SYSTEM = """TETR.IO 전적 분석 페이지 첫 화면에 들어갈 코멘트를 한국어로 씁니다.
입력의 후보는 이 플레이어와 같은 매치 상대의 차이입니다. 괄호 안 방향은 이미 계산된 해석입니다.

할 일
- 후보 중 이 플레이어를 가장 잘 보여 주는 3개를 고릅니다. 중요한 것부터 씁니다.
- 고른 후보마다 한 문장으로 그 차이가 무엇을 뜻하는지 씁니다.
- 마지막에 고른 세 가지를 묶어 총평을 2문장으로 씁니다.

규칙
- 괄호의 방향을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 입력에 없는 숫자, 비율, 백분율은 쓰지 마세요.
- 영어 변수 이름은 쓰지 말고 후보의 한국어 이름을 쓰세요.
- 플레이어를 한 유형으로 규정하지 마세요.
- 같은 번호를 두 번 고르지 마세요.

출력은 아래 4줄만 씁니다. 다른 글은 쓰지 마세요.
번호 | 문장
번호 | 문장
번호 | 문장
총평 | 문장 두 개"""


@dataclass
class LightContext:
    """한 요청의 v3 프롬프트와 번호 → 후보 키 대응."""
    messages: list[dict[str, str]]
    order: list[str]                      # order[i] = (i+1)번 후보의 키
    available: dict[str, float]
    numbers: set[float] = field(default_factory=set)   # 문장에 나와도 되는 숫자
    ranked: list[Standing] = field(default_factory=list)   # v4: 두드러진 순서로 정렬한 후보 (order 와 같은 순서)

    @property
    def standing(self) -> dict[str, Standing]:
        return {s.key: s for s in self.ranked}


def _on(name: str) -> bool:
    return os.getenv(name, "off").lower() in ("on", "1", "true")


def _sgn_word(key: str, v: float) -> str:
    pos, neg, zero = LIGHT_DIRECTIONS[key]
    return pos if v > 0 else neg if v < 0 else zero


def size_label(key: str, v: float) -> str | None:
    half = LIGHT_HALF_RANGE.get(key)
    if not half:
        return None
    r = abs(v) / half
    return "차이 작음" if r < 0.2 else "차이 보통" if r < 0.6 else "차이 큼"


def _allowed_numbers(req: LightRequest, available: dict[str, float]) -> set[float]:
    vals = list(available.values())
    if req.fixed_metrics.win_rate is not None:
        vals.append(req.fixed_metrics.win_rate)
    out: set[float] = set()
    for v in vals:
        a = abs(float(v))
        for c in (a, round(a), round(a, 1), round(a, 2), a * 100, round(a * 100), round(a * 100, 1)):
            out.add(round(c, 4))
    return out


def build_light_v3(req: LightRequest, *, shuffle: bool | None = None, size_hint: bool | None = None,
                   rng: random.Random | None = None) -> LightContext:
    shuffle = _on("LLM_LIGHT_SHUFFLE") if shuffle is None else shuffle
    size_hint = _on("LLM_LIGHT_SIZE_HINT") if size_hint is None else size_hint
    available = req.available_stats()
    order = list(available)                 # 기본: 문서(04 지표 명세 9절) 순서
    if shuffle:
        (rng or random.Random()).shuffle(order)
    lines = []
    if req.fixed_metrics.win_rate is not None:
        lines.append(f"참고: 전체 승률 {round(req.fixed_metrics.win_rate * 100)}%")
    lines.append("후보")
    for i, key in enumerate(order, 1):
        name, meaning = LIGHT_TERMS[key]
        v = available[key]
        tags = [_sgn_word(key, v)]
        if size_hint and (s := size_label(key, v)):
            tags.append(s)
        lines.append(f"[{i}] {name}({meaning}): {v:+g} ({', '.join(tags)})")
    messages = [
        {"role": "system", "content": LIGHT_V3_SYSTEM},
        {"role": "user", "content": "\n".join(lines)},
    ]
    return LightContext(messages, order, available, _allowed_numbers(req, available))


# ---------- 출력 처리 ----------
_LINE = re.compile(r"^\s*\[?\s*(\d{1,2})\s*\]?\s*(?:번)?\s*[|:：.)]\s*(.+?)\s*$")
_SUMMARY = re.compile(r"^\s*\**\s*총평\s*\**\s*[|:：]\s*(.+?)\s*$")
_NUM = re.compile(r"\d+(?:\.\d+)?")
_CJK = re.compile(r"[぀-ヿ一-鿿]")
_LATIN_WORD = re.compile(r"[A-Za-z][A-Za-z_./]*")
# 문서 용어로 쓰이는 영문만 허용
_LATIN_OK = {"opener", "plonk", "stride", "inf", "ds", "app", "weighted", "vs", "apm", "vs/apm",
             "cheese", "index", "tr", "tetr.io", "tetr", "io", "pps"}
_ECHO = ("할 일", "규칙", "출력은 아래", "번호 | 문장", "총평 | 문장", "이미 계산된 해석", "다른 글은 쓰지",
         "고르는 방법", "문장 규칙", "두드러진 순서", "미리 계산", "전체 기준:", "방향:", "지표:", "예시:",
         "고른 후보", "출력 형식", "세로 막대")
# 플레이어를 한 유형으로 규정 (10/13 11번 실측 "… 약한 플레이어를 나타낸다")
_TYPECAST = re.compile(r"플레이어(?:를|임을)\s*(?:나타|보여)|[가-힣]{2,}\s+플레이어(?:입니다|이다|예요|임을|로 보)"
                       r"|플레이스타일을 보여")
_POS = ("높", "강해", "강한", "강합", "강함", "강하", "우위", "오르", "오릅", "올라", "상승", "앞서", "큽", "크고", "큰", "큼")
_NEG = ("낮", "약해", "약한", "약합", "약함", "약하", "떨어", "내리", "내립", "하락", "밀립", "뒤처", "처지", "처집", "작습", "작고", "작은", "작음")


def _first_direction(text: str) -> int:
    best, sign = len(text) + 1, 0
    for words, s in ((_POS, 1), (_NEG, -1)):
        for w in words:
            i = text.find(w)
            if 0 <= i < best:
                best, sign = i, s
    return sign


def _clean(s: str) -> str:
    s = s.strip().strip("\"'`“”‘’*").strip()
    return re.sub(r"^\[\d{1,2}\]\s*", "", s)


def check_sentence(text: str, ctx: LightContext, key: str | None) -> str | None:
    """문제가 있으면 이유, 없으면 None."""
    if not re.search(r"[가-힣]", text):
        return "한국어 문장 아님"
    if _CJK.search(text):
        return "중국어·일본어 섞임"
    bad = [w for w in _LATIN_WORD.findall(text) if w.lower() not in _LATIN_OK]
    if bad:
        return f"영어·변수 이름: {bad[:3]}"
    if any(m in text for m in _ECHO):
        return "프롬프트 베낌"
    if _TYPECAST.search(text):
        return "유형 규정"
    for m in _NUM.findall(text):
        if round(float(m), 4) not in ctx.numbers:
            return f"입력에 없는 숫자: {m}"
    if key is not None:
        v = ctx.available[key]
        d = _first_direction(text)
        # strength_split·delta_comeback 은 문장 구조상 첫 방향 단어가 부호와 바로 대응하지 않을 수 있어 검사하지 않음
        if v and d and key not in ("strength_split", "delta_comeback") and d != (1 if v > 0 else -1):
            return f"방향 반대 ({v:+g})"
    return None


def fallback_summary(keys: list[str], ctx: LightContext) -> str:
    """총평을 쓸 수 없을 때 고른 하이라이트 이름으로 만든다 (선택·해석은 하지 않음)."""
    names = [LIGHT_TERMS[k][0].removesuffix(" 차이") for k in keys]
    return (f"이번 전적에서 상대와 비교해 두드러진 지표는 {', '.join(names)}입니다. "
            "각 지표의 뜻은 아래 하이라이트에 정리했습니다.")


@dataclass
class LightV3Result:
    summary: str
    highlights: list[tuple[str, str]]        # (stat 키, 문장)
    dropped: list[str]                       # 뺀 줄과 이유 (로그용)
    summary_fallback: bool
    picks: list[str] = field(default_factory=list)          # 규칙 검사를 통과한 선택 (문장 검사 전, 고른 순서)
    raw_picks: list[str] = field(default_factory=list)      # 모델이 고른 그대로
    rule_dropped: list[str] = field(default_factory=list)   # 선정 규칙 위반으로 뺀 것
    sentence_dropped: list[str] = field(default_factory=list)   # 문장 검사로 뺀 것 (선택은 규칙 통과)
    model_summary: str | None = None                        # 모델이 쓴 총평 원문 (검사 전)


def process_light_v3(text: str, ctx: LightContext) -> LightV3Result:
    """모델 출력(번호 | 문장 줄)을 하이라이트로 바꾼다. 쓸 수 있는 하이라이트가 0개면 ValueError."""
    return process_lines(text, ctx, rule_check=False)


_REF = re.compile(r"(?:후보\s*\[?(\d{1,2})\]?\s*번?|\[(\d{1,2})\])\s*(?:번\s*)?(?:후보)?(?:의|은|는|이|가)?\s*")


def _ref_in_text(sentence: str, n_max: int) -> tuple[int | None, str]:
    """문장 안의 '후보 8의', '[8]' 를 찾아 (번호, 그 부분을 지운 문장). 없으면 (None, 원문)."""
    m = _REF.search(sentence)
    if not m:
        return None, sentence
    n = int(m.group(1) or m.group(2))
    if not 1 <= n <= n_max:
        return None, sentence
    return n, (sentence[:m.start()] + sentence[m.end():]).strip()


def process_lines(text: str, ctx: LightContext, *, rule_check: bool, allowed: list[str] | None = None,
                  summary_required: bool = True, ref_in_text: bool = False,
                  allow_empty: bool = False) -> LightV3Result:
    """"번호 | 문장" 줄을 처리한다 (v3, A, B 둘째 호출 공통).

    rule_check: 선정 규칙 사후 검사 (A). 선정 검사와 문장 검사를 따로 기록한다
                — 문장이 틀려 뺀 후보도 '고른 것'으로 보고 다음 선택의 규칙 검사에 쓴다 (이중 감점 방지).
    allowed   : 이 키만 받는다 (B 둘째 호출 = 이미 고른 3개).
    ref_in_text: (v4) 문장 안에 '후보 8의'·'[8]' 가 있으면 줄 앞 번호 대신 그 번호를 쓰고 그 부분은 지운다.
    allow_empty: 하이라이트가 0개여도 오류 대신 빈 결과를 돌려준다 (측정에서 뺀 이유를 세기 위해).
    """
    highlights: list[tuple[str, str]] = []
    dropped: list[str] = []
    rule_dropped: list[str] = []
    sentence_dropped: list[str] = []
    raw_picks: list[str] = []
    accepted: list[Standing] = []
    summary = None
    seen: set[str] = set()
    st = ctx.standing
    for raw in text.replace("\r", "").split("\n"):
        line = raw.strip().strip("`").strip()
        if not line:
            continue
        if (m := _SUMMARY.match(line)):
            if summary is None:
                summary = _clean(m.group(1))
            continue
        m = _LINE.match(line)
        if not m:
            continue
        n = int(m.group(1))
        sentence = m.group(2)
        if ref_in_text:   # _clean 이 앞의 "[9]" 를 지우기 전에 읽는다
            ref, sentence = _ref_in_text(sentence, len(ctx.order))
            n = ref or n
        sentence = _clean(sentence)
        if not 1 <= n <= len(ctx.order):
            dropped.append(f"{n}: 없는 번호")
            continue
        key = ctx.order[n - 1]
        if allowed is not None and key not in allowed:
            dropped.append(f"{n}({key}): 고르지 않은 후보")
            continue
        if key in seen:
            dropped.append(f"{n}: 같은 후보 반복")
            continue
        seen.add(key)
        raw_picks.append(key)
        if len(accepted if rule_check else highlights) >= 3:
            dropped.append(f"{n}: 3개 초과")
            continue
        if rule_check:
            if (why := check_pick(accepted, st[key], ctx.ranked)):
                rule_dropped.append(f"{key}: {why}")
                continue
            accepted.append(st[key])
        if (why := check_sentence(sentence, ctx, key)):
            sentence_dropped.append(f"{key}: {why}")
            continue
        highlights.append((key, sentence))
    picks = [s.key for s in accepted] if rule_check else [k for k, _ in highlights]
    dropped += [f"[선정] {d}" for d in rule_dropped] + [f"[문장] {d}" for d in sentence_dropped]
    if not highlights and not allow_empty:
        raise ValueError(f"쓸 수 있는 하이라이트 없음 (뺀 것: {dropped or '형식 불일치'})")
    model_summary = summary
    use_fallback = summary is None or check_sentence(summary, ctx, None) is not None
    if use_fallback:
        if summary_required:
            dropped.append(f"총평: {'없음' if summary is None else check_sentence(summary, ctx, None)}")
        summary = fallback_summary([k for k, _ in highlights], ctx) if highlights else ""
    return LightV3Result(summary, highlights, dropped, use_fallback, picks, raw_picks, rule_dropped,
                         sentence_dropped, model_summary)


# =====================================================================================
# light-v4: A / B / C 프롬프트 (2026-10-13)
# =====================================================================================
LIGHT_V4_VERSION = "light-v4-rules"

# 디코딩: temperature·top_p 는 v1.2 7.2절. max_tokens 는 A·B둘째 = light 377, C 문장 = heavy 119(v1.2 7.2절).
# 선정 24·총평 160 은 이 파일의 제안값 (문서 근거 없음 — 측정 후 출력 토큰 예산 보고서에 반영 필요).
LIGHT_V4_DECODING = {
    "a": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 377},
    "select": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 24},
    "write": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 377},
    "comment": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 119},
    "summary": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 160},
}

_TAGS_EXPLAIN = """후보는 이 플레이어와 같은 매치 상대의 차이입니다. 후보마다 서버가 계산한 내용이 붙어 있습니다.
- 대괄호: 그룹
- 방향: 상대와 비교했을 때의 해석
- 전체 기준: 다른 플레이어들과 비교했을 때 높은 편, 낮은 편, 보통 중 어디인지
후보는 두드러진 순서로 나열되어 있습니다."""

_PICK_RULES = """고르는 방법
- 3개를 고릅니다. 전체 기준이 '높은 편'이나 '낮은 편'인 후보를 '보통'보다 먼저 고릅니다.
- 같은 그룹에서는 하나만 고릅니다.
- 고른 후보는 목록 순서대로 씁니다."""

_WRITE_RULES = """문장 규칙
- 고른 후보마다 한 문장으로 그 차이가 무엇을 뜻하는지 씁니다.
- 방향을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 숫자, 비율, 백분율은 쓰지 마세요.
- 영어 변수 이름은 쓰지 말고 후보의 한국어 이름을 쓰세요.
- 플레이스타일 후보끼리 서로 비교하지 마세요. 잘하고 못함이 아니라 성향입니다.
- 플레이어를 한 유형으로 규정하지 마세요. "~한 플레이어"라고 쓰지 말고 "상대보다 ~하다"로 씁니다.
- 후보 번호나 '고른 후보' 같은 말은 문장에 쓰지 마세요.
- 마지막에 고른 세 가지를 묶어 총평을 2문장으로 씁니다."""

# 10/13 11번 실측: 출력 틀 줄("번호 | 문장", "번호, 번호, 번호")을 그대로 베끼고, 줄 앞에 1·2·3 순서 번호를 쓴 뒤
# 문장 안에 "후보 8의 …" 를 씀 → 틀 줄을 빼고 말로 설명한다. 문장 속 후보 번호는 서버가 읽어 준다(_ref_in_text).
_LINES_OUTPUT = """출력 형식
- 고른 후보 하나에 한 줄씩, 줄 맨 앞에 그 후보의 대괄호 번호를 쓰고 세로 막대(|) 뒤에 문장을 씁니다.
- 줄 맨 앞에는 1, 2, 3 같은 순서 번호가 아니라 후보 목록의 대괄호 번호를 그대로 씁니다.
- 마지막 줄은 '총평'이라고 쓰고 세로 막대(|) 뒤에 총평 두 문장을 씁니다.
- 이 네 줄 말고 다른 글은 쓰지 마세요."""

LIGHT_A_SYSTEM = ("TETR.IO 전적 분석 페이지 첫 화면에 들어갈 코멘트를 한국어로 씁니다.\n"
                  f"{_TAGS_EXPLAIN}\n\n{_PICK_RULES}\n\n{_WRITE_RULES}\n\n{_LINES_OUTPUT}")

LIGHT_SELECT_SYSTEM = ("TETR.IO 전적 분석 페이지 첫 화면에 보여 줄 지표 3개를 고릅니다.\n"
                       f"{_TAGS_EXPLAIN}\n\n{_PICK_RULES}\n\n"
                       "출력: 고른 후보 3개의 대괄호 번호(숫자)만 쉼표로 구분해 한 줄로 씁니다. "
                       "다른 글은 쓰지 마세요.")

LIGHT_WRITE_SYSTEM = ("TETR.IO 전적 분석 페이지 첫 화면에 들어갈 코멘트를 한국어로 씁니다.\n"
                      "아래 후보 3개는 이 플레이어와 같은 매치 상대의 차이이고, 이미 골라 둔 것입니다. "
                      "방향은 서버가 계산한 해석입니다.\n\n"
                      f"{_WRITE_RULES}\n\n{_LINES_OUTPUT}")

LIGHT_COMMENT_SYSTEM = """TETR.IO 전적 분석 페이지 첫 화면의 하이라이트 문장 하나를 한국어로 씁니다.
아래 지표가 이 플레이어에 대해 무엇을 뜻하는지 한 문장으로 쓰세요.

규칙
- 방향을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 숫자, 비율, 백분율은 쓰지 마세요.
- 영어 변수 이름은 쓰지 말고 지표의 한국어 이름을 쓰세요.
- 플레이어를 한 유형으로 규정하지 마세요.
- 문장 하나만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요."""

LIGHT_SUMMARY_SYSTEM = """TETR.IO 전적 분석 페이지 첫 화면의 총평을 한국어로 씁니다.
아래 하이라이트 문장들을 묶어 이 플레이어의 특징을 2문장으로 요약하세요.

규칙
- 하이라이트에 없는 내용과 숫자는 쓰지 마세요.
- 하이라이트 문장을 그대로 옮기지 말고 묶어서 쓰세요.
- 플레이어를 한 유형으로 규정하지 마세요.
- 총평 문장만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요."""

# C 문장 호출의 '해석' 한 줄 — 라이트뷰 프롬프트 초안의 해석 열에서 부호 규칙("양수면 …")을 뺀 것
# (헤비 v3.6: 부호 규칙을 넣으면 그대로 베낌, 방향은 서버가 준다). 역전 주의는 v1.2 5.1절.
LIGHT_NOTES: dict[str, str] = {
    **{k: "잘하고 못함이 아니라 성향입니다." for k in ("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds")},
    "delta_app": "블록을 하나 놓을 때 보내는 공격량입니다.",
    "delta_weighted_app": "가비지 처리 성향을 보정한 공격 효율입니다.",
    "delta_vs_apm": "공격과 수비 중 어느 쪽 비중이 큰지를 보는 성향입니다.",
    "delta_cheese_index": "가비지를 받아 처리하는 성향입니다. 잘하고 못함이 아니라 성향입니다.",
    "strength_split": "대부분의 플레이어는 강한 상대를 만나면 승률이 떨어집니다.",
    "delta_comeback": "몇 판 차이로 뒤졌는지는 단정하지 마세요.",
    "session_vs_slope": "한 매치 안에서의 변화이고, 여러 매치에 걸친 추세가 아닙니다.",
}

# 방향별 기본 문장 (양수, 음수, 0). C 예문 + 대체 문장(LLM_LIGHT_FALLBACK=on). 숫자 없음, check_sentence 통과.
LIGHT_SENTENCES: dict[str, tuple[str, str, str]] = {
    "delta_opener": ("상대보다 초반 공격 위주 성향(Opener)이 강합니다.",
                     "상대보다 초반 공격 위주 성향(Opener)이 약합니다.",
                     "초반 공격 위주 성향(Opener)은 상대와 비슷합니다."),
    "delta_plonk": ("상대보다 효율과 가비지 활용 위주 성향(Plonk)이 강합니다.",
                    "상대보다 효율과 가비지 활용 위주 성향(Plonk)이 약합니다.",
                    "효율과 가비지 활용 위주 성향(Plonk)은 상대와 비슷합니다."),
    "delta_stride": ("상대보다 속도 위주 성향(Stride)이 강합니다.",
                     "상대보다 속도 위주 성향(Stride)이 약합니다.",
                     "속도 위주 성향(Stride)은 상대와 비슷합니다."),
    "delta_inf_ds": ("상대보다 다운스택 위주 성향(Inf DS)이 강합니다.",
                     "상대보다 다운스택 위주 성향(Inf DS)이 약합니다.",
                     "다운스택 위주 성향(Inf DS)은 상대와 비슷합니다."),
    "delta_app": ("상대보다 블록당 공격량(APP)이 많아 공격 효율이 높습니다.",
                  "상대보다 블록당 공격량(APP)이 적어 공격 효율이 낮습니다.",
                  "블록당 공격량(APP)은 상대와 비슷합니다."),
    "delta_weighted_app": ("가비지 처리 성향을 보정해도 상대보다 공격 효율(Weighted APP)이 높습니다.",
                           "가비지 처리 성향을 보정하면 상대보다 공격 효율(Weighted APP)이 낮습니다.",
                           "가비지 처리 성향을 보정한 공격 효율(Weighted APP)은 상대와 비슷합니다."),
    "delta_vs_apm": ("상대보다 공격 대비 수비 비중(VS/APM)이 큽니다.",
                     "상대보다 공격 대비 수비 비중(VS/APM)이 작습니다.",
                     "공격 대비 수비 비중(VS/APM)은 상대와 비슷합니다."),
    "delta_cheese_index": ("상대보다 가비지를 받아 처리하는 성향(Cheese Index)이 강합니다.",
                           "상대보다 가비지를 받아 처리하는 성향(Cheese Index)이 약합니다.",
                           "가비지를 받아 처리하는 성향(Cheese Index)은 상대와 비슷합니다."),
    "strength_split": ("강한 상대를 만나도 승률이 떨어지지 않고 오히려 높은 편입니다.",
                       "강한 상대를 만나면 약한 상대를 만날 때보다 승률이 떨어집니다.",
                       "상대가 강하든 약하든 승률이 비슷합니다."),
    "delta_comeback": ("불리한 경기를 뒤집는 경우가 유리한 경기를 뒤집히는 경우보다 많습니다.",
                       "유리한 경기를 뒤집히는 경우가 불리한 경기를 뒤집는 경우보다 많습니다.",
                       "경기를 뒤집는 경우와 뒤집히는 경우가 비슷합니다."),
    "session_vs_slope": ("한 매치 안에서 라운드가 진행될수록 공방 능력(VS)이 올라갑니다.",
                         "한 매치 안에서 라운드가 진행될수록 공방 능력(VS)이 떨어집니다.",
                         "한 매치 안에서 라운드가 진행되어도 공방 능력(VS)에 뚜렷한 변화가 없습니다."),
}


def template_sentence(key: str, v: float) -> str:
    pos, neg, zero = LIGHT_SENTENCES[key]
    return pos if v > 0 else neg if v < 0 else zero


def _v4_terms(key: str) -> tuple[str, str]:
    # v4 는 프롬프트에 숫자를 하나도 두지 않는다 ("블록 1개당"의 1을 모델이 옮겨 쓰면 숫자 검사에 걸림)
    name, meaning = LIGHT_TERMS[key]
    return name, meaning.replace("블록 1개당", "블록당")


def _candidate_line(n: int, s: Standing) -> str:
    name, meaning = _v4_terms(s.key)
    return f"[{n}] {name}({meaning}) [{s.group}] 방향: {_sgn_word(s.key, s.value)} / 전체 기준: {s.label}"


def _v4_context(req: LightRequest, system: str, cfg: RangeConfig | None) -> LightContext:
    cfg = cfg or load_ranges()
    available = req.available_stats()
    ranked = rank(available, cfg)
    order = [s.key for s in ranked]
    lines = ["후보 (두드러진 순서)"] + [_candidate_line(i, s) for i, s in enumerate(ranked, 1)]
    messages = [{"role": "system", "content": system}, {"role": "user", "content": "\n".join(lines)}]
    # 값을 보여 주지 않으므로 문장에 나와도 되는 숫자는 없다 (numbers 빈 집합)
    return LightContext(messages, order, available, set(), ranked)


def build_light_a(req: LightRequest, cfg: RangeConfig | None = None) -> LightContext:
    return _v4_context(req, LIGHT_A_SYSTEM, cfg)


def build_light_select(req: LightRequest, cfg: RangeConfig | None = None) -> LightContext:
    return _v4_context(req, LIGHT_SELECT_SYSTEM, cfg)


def build_light_write(ctx: LightContext, picks: list[str]) -> list[dict[str, str]]:
    """B 둘째 호출. 번호는 선정 때와 같은 번호를 쓴다 (서버가 키로 바꾸기 쉽게)."""
    st = ctx.standing
    lines = ["고른 후보"] + [_candidate_line(ctx.order.index(k) + 1, st[k]) for k in picks]
    return [{"role": "system", "content": LIGHT_WRITE_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


def build_light_comment(ctx: LightContext, key: str) -> list[dict[str, str]]:
    s = ctx.standing[key]
    name, meaning = _v4_terms(key)
    lines = [f"지표: {name}({meaning})",
             f"해석: {LIGHT_NOTES[key]}",
             f"방향: {_sgn_word(key, s.value)}",
             f"전체 기준: {s.label}",
             f"예시: {template_sentence(key, s.value)}"]
    return [{"role": "system", "content": LIGHT_COMMENT_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


def build_light_summary(highlights: list[tuple[str, str]]) -> list[dict[str, str]]:
    lines = ["하이라이트"] + [f"- {s}" for _, s in highlights]
    return [{"role": "system", "content": LIGHT_SUMMARY_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


_NUM_TOKEN = re.compile(r"\d{1,2}")


def parse_selection(text: str, ctx: LightContext) -> list[str]:
    """선정 호출 출력에서 번호를 순서대로 읽는다 (없는 번호·반복은 버림)."""
    keys: list[str] = []
    for m in _NUM_TOKEN.findall(text):
        n = int(m)
        if 1 <= n <= len(ctx.order) and ctx.order[n - 1] not in keys:
            keys.append(ctx.order[n - 1])
    return keys


def select_with_rules(text: str, ctx: LightContext) -> tuple[list[str], list[str], list[str]]:
    """(모델이 고른 키, 규칙 통과 키, 뺀 이유)."""
    raw = parse_selection(text, ctx)
    accepted, dropped = apply_rules(raw, ctx.ranked)
    return raw, accepted, dropped


_SENT = re.compile(r"[^.!?。]*[.!?。]")
_HEAD = re.compile(r"^\s*(?:문장|하이라이트|총평|답|출력)\s*[:：|]\s*")


def _first_sentences(text: str, k: int) -> str | None:
    para = text.replace("\r", "").strip().split("\n\n")[0].replace("\n", " ")
    para = _HEAD.sub("", para).strip().strip("\"'`“”‘’*- ").strip()
    para = re.sub(r"^\[\d{1,2}\]\s*", "", para)
    sents = [x.strip() for x in _SENT.findall(para) if x.strip()]
    return " ".join(sents[:k]) if sents else None


def process_comment(text: str, ctx: LightContext, key: str) -> tuple[str | None, str | None]:
    """C 문장 호출 출력 → (문장, None) 또는 (None, 이유)."""
    s = _first_sentences(text, 1)
    if not s:
        return None, "완성된 문장 없음"
    if (why := check_sentence(s, ctx, key)):
        return None, why
    return s, None


def process_summary(text: str, ctx: LightContext) -> tuple[str | None, str | None]:
    s = _first_sentences(text, 3)
    if not s:
        return None, "완성된 문장 없음"
    if (why := check_sentence(s, ctx, None)):
        return None, why
    return s, None
