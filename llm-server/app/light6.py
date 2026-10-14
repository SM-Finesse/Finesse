"""라이트 뷰 6회 호출 (light-6call) — 34번 「라이트 뷰 LLM 호출 구조 — 데이터 흐름」 PM 확정안(10/14).

    1단계 판정 1회  : 11개 × {지표, 값, Q1, Q3} → 11개 × {영역(높음/보통/낮음/없음), 벗어난 정도}
    2단계 선정 1회  : 판정 결과(+그룹) → 하이라이트 지표 목록(우선순위 순)
    3단계 코멘트 4회: 개별 ×3 {지표, 값, 영역} → sentence / 기본 ×1 {지표 3개} → light_summary (서로 독립)

이 모듈은 단계별 프롬프트·출력 해석·검증 ①②③을 담는다. 단계를 잇는 것은 백엔드(34번 2절) — 이 서버는
단계마다 엔드포인트를 두고(app/main.py), 검증에 실패하면 502 로 답해 백엔드가 그 호출만 재요청하게 한다.
측정·임시 운영용으로 같은 흐름을 서버 안에서 재현하는 코드는 app/light6_flow.py.

근거
  - 벗어난 정도 계산식·자릿수·허용 오차·동점 규칙: 34번 3.0절
  - 선정 규칙: 31번 2.2절 초안 (선정 주체는 LLM, 해석적 판단 제외 — 34번 1절)
  - 문장 규칙: 31번 1.2(금지 8개)·1.3(비교 기준 표현 5개·서술어)·1.4(지표별 비교 기준)·1.6(분량)
  - 프롬프트·디코딩 세부는 LLM 파트가 정한다(34번 머리말). 아래 [LLM 파트 결정] 표시가 그런 부분.
"""
import json
import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from functools import lru_cache
from pathlib import Path

from app.light_rules import LIGHT_GROUPS
from app.schemas import CANDIDATE_KEYS

LIGHT6_VERSION = "light-6call-v1"
QUARTILES_PATH = Path(__file__).resolve().parent.parent / "config" / "light_quartiles.json"

ZONES = ("높음", "보통", "낮음", "없음")
TOLERANCE = 0.1            # 34번 3.0절: 백엔드 검증 허용 오차 ±0.1

# [LLM 파트 결정] 디코딩. temperature·top_p 는 v1.2 7.2절. 판정 200(11줄), 선정 24, 개별 119(heavy 와 같은 1문장 예산),
# 기본 160 은 제안값 — 측정 후 출력 토큰 예산 보고서에 단계별로 반영(34번 8절).
LIGHT6_DECODING = {
    "judge": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 200},
    "select": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 24},
    "sentence": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 119},
    "summary": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 160},
}

# 화면·문장에 쓰는 이름 (31번 1.3·1.5절 예시의 용어)
NAMES: dict[str, str] = {
    "delta_opener": "Opener 성향",
    "delta_plonk": "Plonk 성향",
    "delta_stride": "Stride 성향",
    "delta_inf_ds": "Inf DS 성향",
    "delta_app": "블록당 공격량(APP)",
    "delta_weighted_app": "보정 공격 효율(Weighted APP)",
    "delta_vs_apm": "수비 비중(VS/APM)",
    "delta_cheese_index": "가비지 처리 성향(Cheese Index)",
    "strength_split": "상대 강도별 승률 차",
    "delta_comeback": "역전 경향",
    "session_vs_slope": "라운드별 공방 능력(VS) 변화",
}
RANK_KEYS = ("delta_app", "delta_weighted_app")                       # 우열 지표 (31번 1.3절)
TENDENCY_KEYS = ("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
                 "delta_vs_apm", "delta_cheese_index")                # 성향 지표
NO_OPPONENT_KEYS = ("strength_split", "session_vs_slope")             # "상대보다" 금지 (31번 1.2-7)

# 31번 1.3절 허용 표현 5개 (+ 예시에 쓰인 "상대보다")
COMPARE_EXPRS = ("상대 대비", "맞붙은 상대들에 비해", "최근 대전 상대들보다", "대전 상대 평균 대비",
                 "매 경기 상대와 견줘", "상대보다", "상대와", "상대들과")   # 뒤 셋: 31번 예시·값이 0일 때 "비슷합니다"


# =====================================================================================
# 0. 계산 (34번 3.0절) — 검증 ①과 측정의 정답
# =====================================================================================
def round1(x: float) -> float:
    """소수 첫째 자리 반올림(사사오입). 파이썬 round 는 은행가 반올림이라 쓰지 않는다."""
    d = Decimal(repr(x)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return float(d)


def judge_one(value: float | None, q1: float, q3: float) -> tuple[str, float | None]:
    """(영역, 벗어난 정도). 영역은 반올림 전 값으로 정한다 — 범위 밖이지만 반올림하면 0 이면 +0.0/-0.0."""
    if value is None:
        return "없음", None
    w = q3 - q1
    if value > q3:
        return "높음", abs(round1((value - q3) / w))
    if value < q1:
        return "낮음", -abs(round1((q1 - value) / w))
    return "보통", 0.0


@lru_cache(maxsize=2)
def _load_quartiles(path: str) -> dict[str, tuple[float, float]]:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))["quartiles"]
    return {k: (float(raw[k][0]), float(raw[k][1])) for k in CANDIDATE_KEYS}


def load_quartiles(path: str | Path | None = None) -> dict[str, tuple[float, float]]:
    return _load_quartiles(str(path or QUARTILES_PATH))


def expected_selection(judgments: dict[str, tuple[str, float | None]], n: int = 3) -> list[str]:
    """선정 규칙(31번 2.2절)을 판정 결과에 그대로 적용한 답 — 측정용(규칙 일치율). 응답에는 쓰지 않는다.

    더 멀리 = |벗어난 정도|(소수 첫째 자리) 큰 것. 같으면 후보 고정 순서(34번 3.0절 동점 규칙).
    규칙 4의 '보통' 단계: 판정 결과에는 범위 끝까지의 거리가 없으므로(보통은 모두 0.0) 고정 순서로 고르고,
    이때도 그룹이 겹치지 않는 쪽을 먼저 고른다. [해석 — PM 확인 필요]
    """
    order = {k: i for i, k in enumerate(CANDIDATE_KEYS)}
    outside = sorted([k for k, (z, _) in judgments.items() if z in ("높음", "낮음")],
                     key=lambda k: (-abs(judgments[k][1]), order[k]))
    normal = sorted([k for k, (z, _) in judgments.items() if z == "보통"], key=lambda k: order[k])
    picks: list[str] = []

    def take(pool, distinct):
        for k in pool:
            if len(picks) >= n or k in picks:
                continue
            if distinct and LIGHT_GROUPS[k] in {LIGHT_GROUPS[p] for p in picks}:
                continue
            picks.append(k)

    take(outside, True)
    take(outside, False)
    take(normal, True)
    take(normal, False)
    return picks


# =====================================================================================
# 1단계 판정
# =====================================================================================
JUDGE_SYSTEM = """TETR.IO 전적 분석에서 지표마다 값이 보통 범위 안에 있는지 판정합니다.
보통 범위는 유저 전체의 1사분위(Q1)부터 3사분위(Q3)까지이고, 폭은 Q3 − Q1 입니다.

판정 방법
- 값이 없으면: 영역 없음, 벗어난 정도는 - 로 씁니다.
- 값이 Q3보다 크면: 영역 높음, 벗어난 정도 = +(값 − Q3) ÷ 폭
- 값이 Q1보다 작으면: 영역 낮음, 벗어난 정도 = −(Q1 − 값) ÷ 폭
- 값이 Q1 이상 Q3 이하면: 영역 보통, 벗어난 정도 0.0
- 벗어난 정도는 소수 첫째 자리까지 반올림해 부호와 함께 씁니다.

계산 예: 값 0.30, Q1 -0.10, Q3 0.10, 폭 0.20 이면 (0.30 − 0.10) ÷ 0.20 = 1.0 이므로 높음, +1.0

출력 형식
- 지표 하나에 한 줄씩, 입력과 같은 순서로 모든 지표를 씁니다.
- 각 줄: 대괄호 번호 | 영역 | 벗어난 정도
- 이 줄들 말고 다른 글은 쓰지 마세요."""


def _fmt(x: float) -> str:
    return f"{x:+.3f}".rstrip("0").rstrip(".") if abs(x) < 1000 else f"{x:+.1f}"


def build_judge(cands: list[tuple[str, float | None, float, float]]) -> list[dict[str, str]]:
    """cands: [(stat, value, q1, q3)] — 후보 고정 순서. 번호 [i] 로 보여 주고 서버가 키로 되돌린다."""
    lines = ["지표"]
    for i, (k, v, q1, q3) in enumerate(cands, 1):
        rng = f"Q1 {_fmt(q1)}, Q3 {_fmt(q3)}, 폭 {q3 - q1:.3f}"
        val = "값 없음" if v is None else f"값 {_fmt(v)}"
        lines.append(f"[{i}] {NAMES[k]}: {val}, {rng}")
    return [{"role": "system", "content": JUDGE_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


_MINUS = str.maketrans({"−": "-", "–": "-", "—": "-", "－": "-", "＋": "+"})
_LEAD_NUM = re.compile(r"^\s*\|?\s*\[?\s*(\d{1,2})\s*\]?")   # 표 줄 "| 1 | 높음 | +0.3 |" 도 읽는다 (10/14 11번)
_ZONE = re.compile(r"(높음|보통|낮음|없음)")
_SIGNED = re.compile(r"(?<![\d.])([+-]?\s*\d+(?:\.\d+)?)")


class StageError(ValueError):
    """단계 출력이 형식·검증을 통과하지 못함 → 엔드포인트는 502 (백엔드가 그 호출만 재요청)."""


@dataclass
class JudgeResult:
    judgments: dict[str, tuple[str, float | None]]   # 모델 출력 그대로 (해석만)
    errors: list[str]                                 # 검증 ① 불일치 (비어 있으면 통과)
    item_ok: dict[str, bool]                          # 지표별 정답 여부 (측정용)


def parse_judge(text: str, cands: list[tuple[str, float | None, float, float]]) -> JudgeResult:
    """모델 출력 → 판정 + 검증 ①. 줄이 하나라도 없으면 그 지표는 오류로 센다."""
    got: dict[str, tuple[str, float | None]] = {}
    for raw in text.translate(_MINUS).replace("\r", "").split("\n"):
        line = raw.strip().strip("`*").strip()
        m = _LEAD_NUM.match(line)
        if not m:
            continue
        n = int(m.group(1))
        if not 1 <= n <= len(cands) or cands[n - 1][0] in got:
            continue
        rest = line[m.end():]
        zm = _ZONE.search(rest)
        if not zm:                       # 입력 줄을 그대로 옮겨 적은 경우 등
            continue
        dm = _SIGNED.search(rest[zm.end():])
        dev = float(dm.group(1).replace(" ", "")) if dm else None
        got[cands[n - 1][0]] = (zm.group(1), dev)
    errors: list[str] = []
    item_ok: dict[str, bool] = {}
    for k, v, q1, q3 in cands:
        zone, dev = judge_one(v, q1, q3)
        if k not in got:
            errors.append(f"{k}: 줄 없음")
            item_ok[k] = False
            continue
        gz, gd = got[k]
        ok = gz == zone
        if ok and zone != "없음":
            ok = gd is not None and abs(gd - dev) <= TOLERANCE + 1e-9
        if not ok:
            want = zone if dev is None else f"{zone} {dev:+.1f}"
            errors.append(f"{k}: {gz} {'' if gd is None else f'{gd:+.1f}'} (정답 {want})")
        item_ok[k] = ok
    return JudgeResult(got, errors, item_ok)


# =====================================================================================
# 2단계 선정
# =====================================================================================
SELECT_SYSTEM = """TETR.IO 전적 분석 페이지 첫 화면에 보여 줄 하이라이트 지표 3개를 고릅니다.
지표마다 판정 결과(영역, 벗어난 정도)와 그룹이 있습니다. 벗어난 정도는 유저 전체의 보통 범위에서 얼마나 멀리 벗어났는지이고, 0.0이면 보통 범위 안입니다.

고르는 규칙
1. 영역이 없음인 지표는 고르지 않습니다.
2. 영역이 높음이나 낮음인 지표를 먼저 고릅니다. 그중 벗어난 정도의 크기(부호를 뺀 값)가 큰 것을 먼저 고릅니다.
3. 같은 그룹에서는 하나만 고릅니다.
4. 3개가 안 차면 3번을 풀고, 그래도 부족하면 영역이 보통인 지표에서 고릅니다.
5. 고른 지표를 두드러진 순서로 씁니다.
강점과 약점을 섞는 것 같은 해석적 판단은 하지 않습니다.

출력: 고른 지표의 대괄호 번호(숫자)만 두드러진 순서로 쉼표로 구분해 한 줄로 씁니다. 다른 글은 쓰지 마세요."""


def build_select(judgments: list[tuple[str, str, float | None]]) -> list[dict[str, str]]:
    """judgments: [(stat, zone, deviation)] — 후보 고정 순서 그대로 (서버가 정렬하지 않는다: 선정은 LLM)."""
    lines = ["지표"]
    for i, (k, z, d) in enumerate(judgments, 1):
        dev = "-" if d is None else f"{d:+.1f}"
        lines.append(f"[{i}] {NAMES[k]} (그룹: {LIGHT_GROUPS[k]}): 영역 {z}, 벗어난 정도 {dev}")
    return [{"role": "system", "content": SELECT_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


@dataclass
class SelectResult:
    stats: list[str]          # 검증 ② 를 통과한 키 (모델 순서, 3개 초과 가능 — 백엔드가 앞 3개)
    raw: list[str]            # 모델이 쓴 번호 → 키 그대로
    dropped: list[str]


def parse_select(text: str, judgments: list[tuple[str, str, float | None]]) -> SelectResult:
    """검증 ② — 11개 키 안 · 서로 다름 · '없음' 아님. 개수 판정(3개 미만 → 재요청)은 호출하는 쪽에서."""
    zone = {k: z for k, z, _ in judgments}
    raw: list[str] = []
    stats: list[str] = []
    dropped: list[str] = []
    nums: list[str] = []
    for line in text.strip().split("\n"):          # 번호가 처음 나오는 줄만 읽는다 (뒤에 설명·입력 베낌이 붙어도 무시)
        nums = re.findall(r"\[(\d{1,2})\]", line) or re.findall(r"(?<![\d.])(\d{1,2})(?![\d.])", line)
        if nums:
            break
    for m in nums:
        n = int(m)
        if not 1 <= n <= len(judgments):
            dropped.append(f"{n}: 없는 번호")
            continue
        k = judgments[n - 1][0]
        raw.append(k)
        if k in stats:
            dropped.append(f"{k}: 반복")
        elif zone[k] == "없음":
            dropped.append(f"{k}: 영역 없음")
        else:
            stats.append(k)
    return SelectResult(stats, raw, dropped)


# =====================================================================================
# 3단계 코멘트 — 개별 ×3, 기본 ×1
# =====================================================================================
def direction_phrase(k: str, v: float) -> str:
    """[LLM 파트 결정] 값의 부호를 서버가 말로 바꿔 준다 (헤비 v3.1~v3.6 에서 효과 확인, 계산은 하지 않음)."""
    s = 1 if v > 0 else -1 if v < 0 else 0
    table = {
        "rank": ("상대보다 앞섬", "상대보다 밀림", "상대와 같음"),
        "delta_vs_apm": ("상대보다 수비 비중이 큼", "상대보다 수비 비중이 작음", "상대와 같음"),
        "tendency": ("상대보다 이 성향이 짙음", "상대보다 이 성향이 옅음", "상대와 같음"),
        "strength_split": ("강한 상대 구간의 승률이 약한 상대 구간보다 오히려 높음",
                           "강한 상대 구간의 승률이 약한 상대 구간보다 낮음", "두 구간의 승률이 같음"),
        "delta_comeback": ("불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강함",
                           "유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강함", "두 경향이 같음"),
        "session_vs_slope": ("라운드가 갈수록 공방 능력이 강해짐", "라운드가 갈수록 공방 능력이 처짐", "뚜렷한 추세 없음"),
    }
    key = "rank" if k in RANK_KEYS else k if k in table else "tendency"
    pos, neg, zero = table[key]
    return pos if s > 0 else neg if s < 0 else zero


def example_sentence(k: str, v: float) -> str:
    """같은 방향의 예문 (숫자 없음). 31번 1.3절 허용 표현과 서술어를 쓴다."""
    name = NAMES[k]
    s = 1 if v > 0 else -1 if v < 0 else 0
    if k in RANK_KEYS:
        return [f"{name}은 상대와 비슷합니다.", f"상대 대비 {name}에서 앞섭니다.", f"상대 대비 {name}에서 밀립니다."][s]
    if k == "delta_vs_apm":
        return ["맞붙은 상대들에 비해 수비 비중이 비슷합니다.", "맞붙은 상대들에 비해 수비 비중이 큰 편입니다.",
                "맞붙은 상대들에 비해 수비 비중이 작은 편입니다."][s]
    if k in TENDENCY_KEYS:
        return [f"맞붙은 상대들과 {name}이 비슷합니다.", f"맞붙은 상대들에 비해 {name}이 짙게 나타납니다.",
                f"맞붙은 상대들에 비해 {name}이 옅게 나타납니다."][s]
    if k == "strength_split":
        return ["강한 상대와 약한 상대를 만날 때의 승률이 비슷합니다.",
                "강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 오히려 높습니다.",
                "강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 낮습니다."][s]
    if k == "delta_comeback":
        return ["불리할 때 뒤집는 경향과 유리하다가 뒤집히는 경향이 비슷합니다.",
                "불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다.",
                "유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강합니다."][s]
    return ["라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다.",
            "라운드가 진행될수록 공방 능력이 강해집니다.",
            "라운드가 진행될수록 공방 능력이 처집니다."][s]


def _compare_rule(k: str) -> str:
    if k == "strength_split":
        return ("비교 기준은 본인 기록 안의 강한 상대 구간과 약한 상대 구간입니다. "
                "'상대보다', '상대 대비'는 쓰지 않습니다. 대부분의 플레이어는 이 값이 음수입니다.")
    if k == "session_vs_slope":
        return "비교 기준은 한 매치 안의 라운드 진행입니다. '상대'라는 말은 쓰지 않습니다."
    if k == "delta_comeback":
        return "비교 기준은 불리할 때 뒤집는 경향과 유리하다가 뒤집히는 경향입니다. 몇 판 차이로 뒤졌는지는 단정하지 않습니다."
    return ("비교 기준을 꼭 씁니다. '상대 대비', '맞붙은 상대들에 비해', '최근 대전 상대들보다', "
            "'대전 상대 평균 대비', '매 경기 상대와 견줘 보면' 중 하나를 고릅니다.")


def _predicate_rule(k: str) -> str:
    if k in RANK_KEYS:
        return "서술어는 '앞섭니다', '밀립니다', '우위', '열세' 같은 말을 씁니다."
    if k in TENDENCY_KEYS:
        return ("서술어는 '짙게/옅게 나타납니다', '큰/작은 편입니다', '강한/약한 편입니다'를 씁니다. "
                "잘하고 못함이 아니라 성향이므로 '앞섭니다', '밀립니다'는 쓰지 않습니다.")
    return "서술어는 방향에 맞게 자연스럽게 씁니다."


COMMON_RULES = """- 방향은 입력의 '방향'을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 값은 괄호 안에 입력 그대로 인용할 수 있습니다. 예: (+0.18). 백분율(%, %p)로 바꾸거나 입력에 없는 숫자를 만들지 마세요.
- 플레이어를 한 유형으로 규정하지 마세요. 주어는 사람이 아니라 지표로 씁니다.
- 연승, 연패, 최근 몇 경기 같은 최근 흐름은 쓰지 마세요.
- 다른 유저나 평균적인 플레이어와 비교하는 말은 쓰지 마세요."""


def build_sentence(k: str, v: float) -> list[dict[str, str]]:
    system = (f"TETR.IO 전적 분석 페이지 첫 화면의 하이라이트 문장 하나를 한국어로 씁니다.\n"
              f"아래 지표 값이 이 플레이어에 대해 무엇을 뜻하는지 한 문장(40~60자)으로 쓰세요.\n\n"
              f"규칙\n- {_compare_rule(k)}\n- {_predicate_rule(k)}\n{COMMON_RULES}\n"
              f"- 문장 하나만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요.")
    user = "\n".join([f"지표: {NAMES[k]}", f"값: {_fmt(v)}", f"방향: {direction_phrase(k, v)}",
                      f"예시: {example_sentence(k, v)}"])
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


SUMMARY_SYSTEM = f"""TETR.IO 전적 분석 페이지 첫 화면의 요약 코멘트를 한국어로 씁니다.
아래 지표 세 개를 묶어 이 플레이어의 전적을 2~3문장(70~100자)으로 요약하세요.

규칙
- 지표마다 비교 기준을 붙입니다. 상대와 비교하는 지표는 '상대 대비', '맞붙은 상대들에 비해', '최근 대전 상대들보다', '대전 상대 평균 대비', '매 경기 상대와 견줘 보면' 중 하나를 씁니다.
- 절이 둘 이상이면 절마다 비교 기준을 붙이거나, 주어를 묶어 비교 기준 하나가 둘 모두에 걸리게 씁니다.
- 상대 강도별 승률 차와 라운드별 변화에는 '상대보다'를 붙이지 않습니다.
- 성향 지표(Opener·Plonk·Stride·Inf DS 성향, 수비 비중, 가비지 처리 성향)는 잘하고 못함으로 쓰지 않습니다.
{COMMON_RULES}
- 요약 문장만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요."""


def build_summary(items: list[tuple[str, float]]) -> list[dict[str, str]]:
    lines = ["지표"] + [f"- {NAMES[k]}: 값 {_fmt(v)}, 방향: {direction_phrase(k, v)}" for k, v in items]
    return [{"role": "system", "content": SUMMARY_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


# ---------- 출력 정리 ----------
_HEAD = re.compile(r"^\s*(?:하이라이트 문장|문장|요약 코멘트|요약|코멘트|총평|답|출력)\s*[:：|]\s*")
_LIST = re.compile(r"^\s*(?:[-*•]|\d{1,2}[.)]|\[\d{1,2}\])\s*")
_SENT = re.compile(r"[^.!?。]*[.!?。]")


def clean_sentences(text: str, k_max: int) -> str | None:
    """머리말 줄(…:)·목록 기호·따옴표를 걷어 내고 앞의 완성된 문장 k_max 개."""
    lines = []
    for raw in text.replace("\r", "").split("\n"):
        line = raw.strip().strip("*").strip()
        if not line or line.endswith((":", "：")):
            continue
        line = _HEAD.sub("", _LIST.sub("", line)).strip().strip("\"'`“”‘’「」*- ").strip()
        if line:
            lines.append(line)
    sents = [s.strip() for s in _SENT.findall(" ".join(lines)) if s.strip()]
    return " ".join(sents[:k_max]) if sents else None


# ---------- 검증 ③ (31번 1.2 금지 8개 · 1.3 비교 기준 · 34번 5절) ----------
_CJK = re.compile(r"[぀-ヿ一-鿿]")
_LATIN = re.compile(r"[A-Za-z][A-Za-z_./]*")
_LATIN_OK = {"opener", "plonk", "stride", "inf", "ds", "app", "weighted", "vs", "apm", "vs/apm", "cheese", "index",
             "tetr.io", "tetr", "io", "tr"}
_NUM = re.compile(r"\d+(?:\.\d+)?")
_ECHO = ("비교 기준을 꼭", "서술어는", "규칙", "지표:", "값:", "방향:", "예시:", "출력", "한 문장(40", "2~3문장")
_TYPECAST = re.compile(r"형 플레이어|플레이어(?:입니다|이다|예요|임을|로 보)|플레이어(?:를|임을)\s*(?:나타|보여)"
                       r"|(?:[A-Za-z]+|[가-힣]*[은는인한])\s+플레이어(?:는|가)\s")
_RECENT = re.compile(r"연승|연패|연속|최근\s*(?:\d+|몇|한두)\s*(?:경기|판|게임)|최근\s*(?:경기|흐름|컨디션|폼)")
_OTHER_USERS = ("다른 유저", "다른 플레이어", "평균적인 플레이어", "비슷한 실력", "전체 유저", "유저들")
_JUDGE_LEAK = ("보통 범위", "범위", "영역", "벗어난 정도", "사분위", "보통")
_RANK_WORDS = ("앞서", "앞섭", "앞선", "밀리", "밀립", "밀려", "밀린", "우위", "열세")
_FORBIDDEN = ("Est", "statrank", "플로킹", "투수율")

_POS = ("앞서", "앞섭", "앞선", "우위", "짙", "큰 편", "크게", "큽", "크고", "큰", "강한 편", "강하게", "강합", "강해", "강하",
        "높", "많", "오르", "올라", "상승", "늘어")
_NEG = ("밀리", "밀립", "밀려", "밀린", "열세", "옅", "작은 편", "작게", "작습", "작고", "작은", "약한 편", "약하게",
        "약합", "약해", "약하", "낮", "적습", "적고", "적은", "적어", "처지", "처집", "처져", "처짐", "떨어", "하락", "줄어", "감소")


def _first_direction(text: str) -> int:
    best, sign = len(text) + 1, 0
    for words, s in ((_POS, 1), (_NEG, -1)):
        for w in words:
            i = text.find(w)
            if 0 <= i < best:
                best, sign = i, s
    return sign


def _comeback_direction(text: str) -> int:
    """+1 뒤집는 쪽이 강/많음, -1 뒤집히는 쪽, 0 판단 불가. 'A가 B보다 …' 이면 '보다' 바로 앞(B)이 약한 쪽."""
    i = text.find("보다")
    if i >= 0:
        before = text[:i]
        kinds = ["히" if before[m.end():m.end() + 1] == "히" else "는" for m in re.finditer("뒤집", before)]
        if "히" in kinds and "는" in kinds:
            dom = 1 if kinds[-1] == "히" else -1
            after = text[i + 2:]
            if _first_direction(after) == -1:
                dom = -dom
            return dom
        return 0
    has_hi = "뒤집히" in text
    has_neun = bool(re.search(r"뒤집(?!히)", text))
    if has_hi != has_neun:
        d = -1 if has_hi else 1
        return -d if _first_direction(text) == -1 else d
    return 0


def _allowed_numbers(values: list[float]) -> set[float]:
    out: set[float] = set()
    for v in values:
        a = abs(float(v))
        for c in (a, round(a, 1), round(a, 2), round(a, 3), round1(a)):
            out.add(round(c, 4))
    return out


def _ends_with_predicate(text: str) -> bool:
    t = re.sub(r"\s*\([^()]*\)\s*([.!?。]?)\s*$", r"\1", text.strip())     # 끝의 값 인용 "(+0.02)." 는 떼고 본다
    t = re.sub(r"[\s.!?。\"'”’)\]]+$", "", t)
    if not t:
        return False
    if t[-1] in "다요죠":
        return True
    code = ord(t[-1]) - 0xAC00
    return 0 <= code < 11172 and code % 28 == 16


def check_comment(text: str, items: list[tuple[str, float]], single: bool) -> str | None:
    """검증 ③. 문제가 있으면 이유, 없으면 None. items = [(stat, value)] (개별이면 1개)."""
    if not re.search(r"[가-힣]", text):
        return "한국어 문장 아님"
    if not _ends_with_predicate(text):
        return "문장이 아님(서술어 없음)"
    if _CJK.search(text):
        return "중국어·일본어 섞임"
    bad = [w for w in _LATIN.findall(text) if w.lower() not in _LATIN_OK]
    if bad:
        return f"영어·변수 이름: {bad[:3]}"
    if any(m in text for m in _ECHO):
        return "프롬프트 베낌"
    if "%" in text or "퍼센트" in text or "%p" in text:
        return "백분율"
    if any(w in text for w in _FORBIDDEN):
        return "금지어"
    if _TYPECAST.search(text):
        return "유형 규정"
    if _RECENT.search(text):
        return "최근 흐름"
    if any(w in text for w in _OTHER_USERS):
        return "다른 유저와 비교"
    if any(w in text for w in _JUDGE_LEAK):
        return "판정 정보 노출"
    allowed = _allowed_numbers([v for _, v in items])
    for m in _NUM.findall(text):
        if round(float(m), 4) not in allowed:
            return f"입력에 없는 숫자: {m}"
    keys = [k for k, _ in items]
    needs_compare = [k for k in keys if k.startswith("delta_") and k != "delta_comeback"]
    if needs_compare and not any(e in text for e in COMPARE_EXPRS):
        return "비교 기준 누락"
    if single:
        k, v = items[0]
        if k in NO_OPPONENT_KEYS and ("상대보다" in text or "상대 대비" in text):
            return "비교 기준 잘못(상대)"
        if k == "session_vs_slope" and "상대" in text:
            return "비교 기준 잘못(상대)"
        if k in TENDENCY_KEYS and any(w in text for w in _RANK_WORDS):
            return "성향에 우열 서술어"
        if k in TENDENCY_KEYS and re.search(r"잘하|못하|잘한|못한", text):
            return "성향에 우열 서술어"
        want = 1 if v > 0 else -1 if v < 0 else 0
        if k == "delta_comeback":
            d = _comeback_direction(text)
        elif k == "strength_split":
            d = 0                       # 문장 구조가 다양해 방향 검사 제외 (예문으로 유도)
        else:
            d = _first_direction(text)
        if k == "session_vs_slope" and want == 0:
            if "추세" not in text and "변화" not in text:
                return "추세 없음을 쓰지 않음"
            return None
        if want and k != "strength_split":
            if d == 0:
                return "방향 없음"
            if d != want:
                return f"방향 반대 ({v:+g})"
    return None


def parse_sentence(text: str, k: str, v: float) -> tuple[str | None, str | None]:
    s = clean_sentences(text, 1)
    if not s:
        return None, "완성된 문장 없음"
    why = check_comment(s, [(k, v)], single=True)
    return (None, why) if why else (s, None)


def parse_summary(text: str, items: list[tuple[str, float]]) -> tuple[str | None, str | None]:
    s = clean_sentences(text, 3)
    if not s:
        return None, "완성된 문장 없음"
    why = check_comment(s, items, single=False)
    return (None, why) if why else (s, None)
