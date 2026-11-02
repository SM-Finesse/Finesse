"""라이트뷰 코멘트 (light-v5) — 개별 코멘트 ×3 + 기본 코멘트 ×1.

근거: 「코멘트 작성 가이드라인」 31번 (10/16 개정판) 2.1~2.5절
  - 판정·선정은 calc(데이터 파트)가 한다. LLM 은 설명만 쓴다.
  - 개별 코멘트 입력: {지표, 값, 영역}. 기본 코멘트 입력: 선정된 3개. 승률·TR 은 받지 않는다.
  - 값은 소수 둘째 자리(Cheese Index 는 첫째 자리).
  - 영역 높음/낮음 → 차이가 두드러진다. 보통 → 차이가 크지 않다(부호는 지킨다, 과장 금지).
    예: "블록당 공격량은 비슷한 수준입니다(+0.01)" 허용.
  - delta_comeback 은 경향으로만 쓴다.
  - 2.5절 미결: strength_split 의 영역과 부호가 반대인 경우(-0.10 = 높음), 지표 이름(가칭).

설계 (헤비 v4.3 과 같은 방식)
  - 서버가 영역·부호를 서술형 방향 문장으로 바꿔 준다. '보통'·'범위' 같은 판정 용어는 모델에 주지 않는다
    (10/14 C v1: 영역을 그대로 주면 문장에 "보통"이 새어 나옴).
  - 비교 표현 5개 중 하나를 요청마다 무작위로 골라 예시에 넣는다.
  - 예시는 대체 문장 빌더(show=False)로 만든다 → 예시와 대체 문장이 같은 규칙을 따른다.
  - 검사를 통과하지 못하면 대체 문장(예시 + 값)을 보낸다.
"""
from __future__ import annotations

import random
import re

from app.fallback import _subject, _topic
from app.light6 import (
    _CJK,
    _ECHO,
    _FORBIDDEN,
    _LATIN,
    _NUM,
    _OTHER_USERS,
    _RANK_WORDS,
    _RECENT,
    _TYPECAST,
    RANK_KEYS,
    TENDENCY_KEYS,
    _comeback_direction,
    _ends_with_predicate,
    _first_direction,
    judge_one,
    load_quartiles,
)
from app.light6 import (
    _LATIN_OK as _LATIN_OK6,
)
from app.names import COMEBACK_LOSE, COMEBACK_WIN, LATIN_NAME_WORDS, normalize_names
from app.names import COMMENT_NAMES as NAMES  # v5.5: 31번 1.8절 이름 (PM 확정 10/26)
from app.prompt import COMPARE_PHRASES, HEAVY_RANGES
from app.validation import _BASIS, _INTENSIFIERS

LIGHT_COMMENT_VERSION = "light-v5.6d-p1rules-20261027"
# v5.6 (10/26 저녁): 「P1 프롬프트에 넣을 규칙 — 최종(최소안)」(정본 claude/31) — 학습 데이터(작업자 문장)와 같은 규칙.
#   Infinite Downstack 성향, delta_comeback 원값, 숫자는 괄호에 부호째·다시 반올림 금지(요약은 숫자 없음),
#   strength_split "…승률이 떨어집니다/크게 흔들리지 않습니다/오히려 높습니다", session "오릅니다/처집니다", 보통 "~하는 편·흐름",
#   요약: 70~100자, 지표마다 절, 대비·인과 연결("다만·반면·하지만·~해서·덕분에")·종합 평가 금지, 개별 문장 이어 붙이기 금지.
# v5.6b (10/27): 25번 실측(개별 99%·기본 93%)에서 잘못 통과한 요약 — 첫머리 틀과 끝말 불일치('…점은 … 없습니다'),
#   틀 없이 '…다는 것을 눈에 띄게 합니다', 지표 이름 반복, 지표마다 한 문장씩 이어 붙이기 → 검사 추가. 요약 지시에서 '이 플레이어의 전적을' 뺌.
# v5.6c (10/27): v5.6b 실측 — 요약 지시를 바꾸자 입력 줄('이름: 값 …, 방향: …') 베끼기가 늘어 기본 67%(v5.6 93%) → 요약 지시는 v5.6 문구로 되돌림.
#   정리·검사(v5.6b)는 유지하고 더함: 입력 줄 꼴('이름: 값 …, 방향: 문장')은 방향 문장만 남기고, 마침표 없는 줄에 마침표,
#   '…옅습니다, …'(문장 끝 뒤 쉼표)는 '…옅고, …'로. 가비지 처리 성향·플레이스타일의 '높은/낮은 편' 거부(P1 규칙 2절).
# v5.6d (10/27): 프롬프트·검사는 v5.6c 와 같음. 이름 정리(names.py)에 소문자 'opener/plonk/stride' → 'Opener/Plonk/Stride'만 더함(헤비와 공통).
_LATIN_OK = set(_LATIN_OK6) | LATIN_NAME_WORDS

# temperature·top_p: v1.2 7.2절. max_tokens: 개별 119(헤비 1문장 예산과 같음), 기본 160 (light6 와 같음)
LIGHT_COMMENT_DECODING = {
    "sentence": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 119},
    "summary": {"temperature": 0.4, "top_p": 0.9, "max_tokens": 160},
}

ZONE_NAMES = ("높음", "보통", "낮음")
# 31번 2.3절 Q1·Q3 (train-2026-10-14) — 영역이 안 왔을 때만 쓴다. 헤비 보통 범위와 같은 표 + 역전 경향
_QUARTILES = {**HEAVY_RANGES, "delta_comeback": (-0.046, 0.050)}
# 값이 0 일 때 쓰는 비교 표현 — "최근 대전 상대들보다 … 비슷한 수준" 은 어색해서 뺀다
_ZERO_PHRASES = tuple(p for p in COMPARE_PHRASES if not p.endswith("보다"))
# 비교 기준이 상대인 지표 (31번 1.3절). comeback 은 경향으로만 쓰므로 뺀다 (2.4절)
COMPARE_KEYS = tuple(k for k in NAMES if k.startswith("delta_") and k != "delta_comeback")

# 문장에 그 지표가 들어 있는지 볼 때 쓰는 낱말 (하나라도 있으면 됨, 대소문자 무시)
STAT_WORDS: dict[str, tuple[str, ...]] = {
    "delta_opener": ("opener",),
    "delta_plonk": ("plonk",),
    "delta_stride": ("stride",),
    "delta_inf_ds": ("infinite downstack", "inf ds", "inf_ds", "다운스택"),
    "delta_app": ("공격력", "공격량", "app"),
    "delta_weighted_app": ("weighted", "보정 공격"),
    "delta_vs_apm": ("수비",),
    "delta_cheese_index": ("가비지", "cheese"),
    "strength_split": ("강한 상대",),
    "delta_comeback": ("뒤집", "역전"),
    "session_vs_slope": ("라운드",),
}

_VAGUE = ("크지 않", "비슷", "같은 수준", "차이가 없", "차이는 없")


# =====================================================================================
# 값·영역
# =====================================================================================
def round_value(k: str, v: float) -> float:
    """P1 규칙 1절: 소수 둘째 자리, 가비지 처리 성향(delta_cheese_index)은 첫째 자리, delta_comeback 은 원값."""
    if k == "delta_comeback":
        return float(v)
    return round(float(v), 1 if k == "delta_cheese_index" else 2)


def fmt_value(k: str, v: float) -> str:
    """입력·괄호 인용에 쓰는 값 — 부호를 붙인다(+0.37 / -0.66). 모델은 이 글자 그대로 인용한다(다시 반올림 ✗)."""
    r = round_value(k, v)
    if k == "delta_comeback":
        return "0" if r == 0 else f"{r:+}"
    if r == 0:
        return "0.0" if k == "delta_cheese_index" else "0.00"
    return f"{r:+.1f}" if k == "delta_cheese_index" else f"{r:+.2f}"


def resolve_zone(k: str, v: float, zone: str | None) -> str:
    """calc 가 준 영역을 쓴다. 없으면(연동 시험용) 31번 2.3절 Q1·Q3 로 직접 판정."""
    if zone in ZONE_NAMES:
        return zone
    try:
        q1, q3 = load_quartiles()[k]
    except (FileNotFoundError, KeyError):         # config/light_quartiles.json 이 없는 서버 (11·13번)
        q1, q3 = _QUARTILES[k]
    return judge_one(float(v), q1, q3)[0]


SOFT_STATES = ("weak+", "weak-", "mid+", "mid-", "zero")


def state(k: str, v: float, zone: str) -> str:
    """문장 상태 — 31번 2.2절 A안(10/26 확정): 방향은 값의 부호, 강도는 영역.
      'high'·'low'   같은 쪽 (양수·높음 / 음수·낮음) → 뚜렷하게
      'weak+'·'weak-' 반대쪽 (양수·낮음 / 음수·높음) → 방향은 부호대로, 폭이 작다·잘 버틴다
                      (calc 티어 묶음 범위(G2)에서 생김 — 예: 상위 티어 session_vs_slope +0.6 '낮음')
      'mid+'·'mid-'  보통 → '~하는 편'
      'zero'         값이 0 → '같은 수준'"""
    r = round_value(k, v)
    if r == 0:
        return "zero"
    sign = "+" if r > 0 else "-"
    if zone == "보통":
        return "mid" + sign
    if (zone == "높음" and r > 0) or (zone == "낮음" and r < 0):
        return "high" if r > 0 else "low"
    return "weak" + sign


# =====================================================================================
# 방향 문장 (서술형 — 헤비 v4.3 교훈: 명사형 꼬리표는 모델이 그대로 옮김)
# =====================================================================================
def direction(k: str, st: str) -> str:
    """상대를 넣지 않은 서술형 방향. 프롬프트 '방향' 줄과 예시·대체 문장이 같이 쓴다 (31번 2.2절 표·예시)."""
    name = NAMES[k]
    t, s = _topic(name), _subject(name)
    if k in RANK_KEYS:
        return {"high": f"{name}에서 뚜렷하게 앞섭니다", "low": f"{name}에서 뚜렷하게 밀립니다",
                "weak+": f"{name}에서 조금 앞서지만 그 폭은 작은 편입니다",
                "weak-": f"{name}에서 조금 밀리지만 그 폭은 작은 편입니다",
                "mid+": f"{name}에서 앞서는 편입니다", "mid-": f"{name}에서 밀리는 편입니다",
                "zero": f"{name}{t} 같은 수준입니다"}[st]
    if k == "delta_vs_apm":
        return {"high": f"{name}{s} 뚜렷하게 큰 편입니다", "low": f"{name}{s} 뚜렷하게 작은 편입니다",
                "weak+": f"{name}{s} 조금 큰 편이지만 그 폭은 크지 않습니다",
                "weak-": f"{name}{s} 조금 작은 편이지만 그 폭은 크지 않습니다",
                "mid+": f"{name}{s} 큰 편입니다", "mid-": f"{name}{s} 작은 편입니다",
                "zero": f"{name}{t} 같은 수준입니다"}[st]
    if k in TENDENCY_KEYS:
        return {"high": f"{name}{s} 뚜렷하게 짙습니다", "low": f"{name}{s} 뚜렷하게 옅습니다",
                "weak+": f"{name}{s} 조금 짙지만 그 폭은 작은 편입니다",
                "weak-": f"{name}{s} 조금 옅지만 그 폭은 작은 편입니다",
                "mid+": f"{name}{s} 짙은 편입니다", "mid-": f"{name}{s} 옅은 편입니다",
                "zero": f"{name}{t} 같은 수준입니다"}[st]
    if k == "strength_split":   # P1 규칙 2절 틀 "강한 상대 구간에서는 약한 상대 구간보다 승률이 …" (떨어진다·흔들리지 않는다·오히려 높다)
        head = "강한 상대 구간에서는 약한 상대 구간보다 승률이"
        return {"low": f"{head} 크게 떨어집니다",
                "mid-": f"{head} 떨어지는 편입니다",
                "weak-": "강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편입니다",   # 7절 예시
                "high": f"{head} 오히려 높습니다",
                "mid+": f"{head} 오히려 높은 편입니다",
                "weak+": f"{head} 오히려 조금 높지만 그 폭은 작은 편입니다",
                "zero": "강한 상대 구간에서는 약한 상대 구간과 승률이 같은 수준입니다"}[st]
    if k == "delta_comeback":
        a, b = f"{COMEBACK_WIN}이 {COMEBACK_LOSE}보다", f"{COMEBACK_LOSE}이 {COMEBACK_WIN}보다"   # 31번 1.8절
        return {"high": f"{a} 뚜렷하게 강합니다", "low": f"{b} 뚜렷하게 강합니다",
                "weak+": f"{a} 조금 강하지만 그 폭은 작은 편입니다", "weak-": f"{b} 조금 강하지만 그 폭은 작은 편입니다",
                "mid+": f"{a} 강한 편입니다", "mid-": f"{b} 강한 편입니다",
                "zero": f"{COMEBACK_WIN}과 {COMEBACK_LOSE}이 같은 수준입니다"}[st]
    # session_vs_slope
    head = "라운드가 진행될수록 공방 능력이"   # 31번 1.8절
    # P1 규칙 2절 서술어 "오른다·처진다", 4절 보통 "~하는 흐름", 7절 예시 "오르는 흐름입니다"
    return {"high": f"{head} 뚜렷하게 오릅니다", "low": f"{head} 뚜렷하게 처집니다",
            "weak+": f"{head} 오르기는 하지만 그 폭은 작은 편입니다",
            "weak-": f"{head} 처지기는 하지만 그 폭은 작은 편입니다",
            "mid+": f"{head} 오르는 흐름입니다", "mid-": f"{head} 처지는 흐름입니다",
            "zero": "라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다"}[st]


def _degree(st: str) -> str:
    return "strong" if st in ("high", "low") else "weak" if st.startswith("weak") else st.rstrip("+-")


def pick_phrase(st: str, rng: random.Random | None = None) -> str:
    return (rng or random).choice(_ZERO_PHRASES if st == "zero" else COMPARE_PHRASES)


def build_sentence_text(k: str, v: float, zone: str, phrase: str, show: bool = True) -> str:
    """예시(show=False)·대체 문장(show=True). 값은 끝에 괄호로 붙인다."""
    st = state(k, v, zone)
    body = direction(k, st)
    if k in COMPARE_KEYS:
        body = f"{phrase} {body}"
    return f"{body}({fmt_value(k, v)})." if show else f"{body}."


# =====================================================================================
# 프롬프트
# =====================================================================================
# P1 규칙 5절 금지 (개별·요약 공통)
COMMON_RULES = """- 플레이어를 한 유형으로 규정하지 마세요('~형 플레이어'). 주어는 사람이 아니라 지표로 씁니다.
- 연승, 연패, 최근 몇 경기, 티어, TR은 쓰지 마세요.
- 다른 유저와 비교하지 마세요('같은 티어', '다른 유저들보다', '평균적인 플레이어보다', '비슷한 실력의 상대보다', '드물게').
- '~해야 합니다' 같은 조언은 쓰지 마세요."""
# P1 규칙 1절 숫자 인용 (개별) — 요약은 숫자를 쓰지 않는다 (6절)
NUMBER_RULE = ("- 값을 쓰려면 문장 끝 괄호에 입력 그대로, 부호를 붙여 씁니다. 예: (+0.18). 다시 반올림하지 말고, "
               "괄호 밖에는 숫자를 쓰지 마세요. 값은 빼도 됩니다. 백분율(%, %p)로 바꾸거나 입력에 없는 숫자를 만들지 마세요.")


def _compare_rule(k: str, phrase: str) -> str:
    if k == "strength_split":
        return "비교 기준은 본인 기록 안의 강한 상대 구간과 약한 상대 구간입니다. '상대보다', '상대 대비'는 쓰지 않습니다."
    if k == "session_vs_slope":
        return "비교 기준은 한 매치 안의 라운드 진행입니다. '상대'라는 말은 쓰지 않습니다."
    if k == "delta_comeback":
        return (f"비교 기준은 {COMEBACK_WIN}(불리할 때 뒤집는 경향)과 {COMEBACK_LOSE}(유리하다가 뒤집히는 경향)입니다. "
                f"'{COMEBACK_WIN}', '{COMEBACK_LOSE}'이라는 말로 쓰고, 몇 판 차이인지는 단정하지 않습니다.")
    return f"비교 기준으로 '{phrase}'를 문장 앞에 그대로 씁니다."


_NO_RANK = "잘하고 못함이 아니라 성향이므로 '앞섭니다', '밀립니다', '강점', '약점'은 쓰지 않습니다."


def _predicate_rule(k: str) -> str:
    """P1 규칙 2절 지표 사전의 서술어."""
    if k in RANK_KEYS:
        return "서술어는 '앞섭니다', '밀립니다'를 씁니다. '높습니다', '강합니다'는 쓰지 않습니다."
    if k == "delta_vs_apm":    # v5.1: 낮음인데 '짙게'로 쓴 경우가 5/5회 실패의 원인 (10/20 25번)
        return f"서술어는 '큽니다', '작습니다'(큰/작은 편)만 씁니다. '짙게', '옅게'는 쓰지 않습니다. {_NO_RANK}"
    if k == "delta_cheese_index":
        return f"서술어는 '짙습니다/옅습니다' 또는 '강합니다/약합니다'를 씁니다. '크다/작다'는 쓰지 않습니다. {_NO_RANK}"
    if k in TENDENCY_KEYS:     # 플레이스타일 4개
        return f"서술어는 '짙습니다', '옅습니다'(짙은/옅은 편)만 씁니다. '크다/작다', '강하다/약하다'는 쓰지 않습니다. {_NO_RANK}"
    return "서술어는 방향 문장을 따릅니다."


def build_sentence(k: str, v: float, zone: str, rng: random.Random | None = None) -> list[dict[str, str]]:
    st = state(k, v, zone)
    phrase = pick_phrase(st, rng)
    degree = {
        "strong": ("- 방향 문장대로 차이가 분명하다고 씁니다('뚜렷하게', '눈에 띄게', '확연히', '분명하게', '크게' 중 하나). "
                   "'비슷합니다', '크지 않습니다'로 흐리지 마세요."),
        "weak": ("- 방향은 지키되 '조금', '소폭', '~하긴 하지만 그 폭은 작은 편'처럼 폭이 작다고 씁니다. "
                 "'뚜렷하게', '크게' 같은 말로 부풀리지 마세요."),
        "mid": ("- '~하는 편입니다', '~하는 흐름입니다'처럼 방향만 담담하게 씁니다. '뚜렷하게', '크게'는 쓰지 말고, "
                "값이 0이 아니므로 '같은 수준', '비슷합니다'도 쓰지 마세요."),
        "zero": "- 값이 0이므로 '같은 수준입니다'로 씁니다.",
    }[_degree(st)]
    system = (f"TETR.IO 전적 분석 페이지 첫 화면의 하이라이트 문장 하나를 한국어로 씁니다.\n"
              f"아래 지표 값이 이 플레이어에 대해 무엇을 뜻하는지 한 문장(40~60자)으로 쓰세요.\n\n"
              f"규칙\n- {_compare_rule(k, phrase)}\n- {_predicate_rule(k)}\n"
              f"- 방향은 입력의 '방향'을 그대로 따르세요. 값의 부호를 다시 해석하지 마세요.\n{degree}\n"
              f"- 이 지표 하나만 씁니다. 다른 지표는 말하지 마세요.\n{NUMBER_RULE}\n"
              f"{COMMON_RULES}\n- 문장 하나만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요.")
    user = "\n".join([f"지표: {NAMES[k]}", f"값: {fmt_value(k, v)}", f"방향: {direction(k, st)}",
                      f"예시: {build_sentence_text(k, v, zone, phrase, show=False)}"])
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


SUMMARY_SYSTEM = f"""TETR.IO 전적 분석 페이지 첫 화면의 요약 코멘트를 한국어로 씁니다.
아래 지표 세 개를 묶어 이 플레이어의 전적을 2~3문장(70~100자)으로 요약하세요.

규칙
- 세 지표를 모두 입력 순서대로 다룹니다. 지표마다 입력의 '방향'을 그대로 따르고, 강도도 방향 문장과 같게 씁니다.
- 지표마다 절을 나눕니다. 두 지표를 한 주어로 묶지 마세요('A와 B 모두 …' ✗).
- 상대와 비교하는 지표는 '상대 대비', '맞붙은 상대들에 비해', '최근 대전 상대들보다', '대전 상대 평균 대비', '매 경기 상대와 견줘 보면' 중 하나를 붙입니다. 절이 둘 이상이면 절마다 붙이세요.
- 상대 강도별 승률, 역전승·역전패 경향, 라운드별 공방 능력 변화에는 '상대보다'를 붙이지 않습니다.
- 지표 이름은 입력의 이름 그대로 쓰고 영어 약어(APP, VS/APM, Cheese Index, VS)는 쓰지 마세요.
- 방향 문장이 '~하는 편입니다', '~하는 흐름입니다'나 '그 폭은 작은 편'인 지표는 '크게', '뚜렷하게' 같은 말로 부풀리지 마세요. 값이 0이 아닌 지표를 '같은 수준', '비슷하다'로 쓰지 마세요.
- 성향 지표(Opener·Plonk·Stride·Infinite Downstack 성향, 수비 비중, 가비지 처리 성향)는 잘하고 못함으로 쓰지 않고, 플레이스타일끼리 비교하지 않습니다('가장', '특히' ✗).
- 숫자(값)는 쓰지 마세요. 지표 사이를 '다만', '반면', '하지만', '~해서', '~ 덕분에'로 잇지 말고, '전반적으로 잘한다' 같은 종합 평가도 쓰지 마세요.
- 첫머리는 '이번 분석에서 눈에 띄는 점은 …', '대전 기록에서 두드러지는 부분은 …'처럼 시작해도 됩니다. 개별 문장을 그대로 이어 붙이지 마세요.
- 승률은 입력에 없으므로 쓰지 않습니다.
- 지표 이름 뒤에 쌍점(:)을 붙여 나열하지 말고, '이 플레이어는'으로 시작하지 마세요.
- 상대 강도별 승률은 '강한 상대 구간에서는 약한 상대 구간보다 승률이 …'처럼 강한 상대 구간을 앞에 두는 순서를 바꾸지 말고 방향 문장 그대로 씁니다.
{COMMON_RULES}
- 요약 문장만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요."""


def build_summary(items: list[tuple[str, float, str]], rng: random.Random | None = None) -> list[dict[str, str]]:
    """items = [(stat, value, zone)] (calc 가 고른 3개)."""
    lines = ["지표"]
    for k, v, z in items:
        lines.append(f"- {NAMES[k]}: 값 {fmt_value(k, v)}, 방향: {direction(k, state(k, v, z))}")
    lines.append(f"예시: {build_summary_text(items, rng, show=False)}")
    return [{"role": "system", "content": SUMMARY_SYSTEM}, {"role": "user", "content": "\n".join(lines)}]


# 방향 문장의 끝말 → (절을 잇는 꼴 '~고', 인용 꼴 '~다는'). 대체 요약을 개별 문장의 단순 이어 붙이기가 아니게 만든다.
_ENDINGS = {
    "앞섭니다": ("앞서고", "앞선다는"), "밀립니다": ("밀리고", "밀린다는"), "짙습니다": ("짙고", "짙다는"),
    "옅습니다": ("옅고", "옅다는"), "강합니다": ("강하고", "강하다는"), "오릅니다": ("오르고", "오른다는"),
    "처집니다": ("처지고", "처진다는"), "떨어집니다": ("떨어지고", "떨어진다는"), "높습니다": ("높고", "높다는"),
    "않습니다": ("않고", "않는다는"), "없습니다": ("없고", "없다는"), "편입니다": ("편이고", "편이라는"),
    "흐름입니다": ("흐름이고", "흐름이라는"), "수준입니다": ("수준이고", "수준이라는"),
}
_SUMMARY_OPENERS = ("이번 분석에서 눈에 띄는 점은", "대전 기록에서 두드러지는 부분은")   # P1 규칙 6절 첫머리 틀


def _clause(k: str, v: float, z: str, phrase: str) -> tuple[str, str]:
    """(본문, 끝말). 본문 + 끝말 + '.' = 개별 대체 문장(숫자 없음)."""
    body = build_sentence_text(k, v, z, phrase, show=False)[:-1]
    for end in _ENDINGS:
        if body.endswith(end):
            return body[:-len(end)], end
    raise ValueError(f"끝말 표에 없음: {body}")


def build_summary_text(items: list[tuple[str, float, str]], rng: random.Random | None = None,
                       show: bool = True) -> str:
    """대체 요약 (P1 규칙 6절) — 첫 지표는 첫머리 틀 "… 점은 ~다는 것입니다", 나머지는 절마다 비교 기준을 붙여
    '~고'로 잇는다. 숫자는 쓰지 않는다(show 는 예전 호출과 맞추려고 남김). 대비·인과 연결어는 쓰지 않는다."""
    rng = rng or random
    parts = [_clause(k, v, z, pick_phrase(state(k, v, z), rng)) for k, v, z in items]
    (b0, e0), rest = parts[0], parts[1:]
    out = [f"{rng.choice(_SUMMARY_OPENERS)} {b0}{_ENDINGS[e0][1]} 것입니다."]
    if rest:
        out.append(", ".join(f"{b}{_ENDINGS[e][0]}" for b, e in rest[:-1])
                   + (", " if len(rest) > 1 else "") + f"{rest[-1][0]}{rest[-1][1]}.")
    return " ".join(out)


# =====================================================================================
# 정리 (v5.1, 10/20 25번 측정) — 뜻을 바꾸지 않는 기계적 손질만 한다
# =====================================================================================
_PLAYER_HEAD = re.compile(r"(^|(?<=[.!?]\s))이 (?:플)?레이어(?:는|가|의 전적은|의)\s+")   # '이 레이어는'(오타) 포함
# v5.2: 소수점(+0.08)에서 문장이 잘리지 않게 — 숫자 사이의 점은 문장 끝이 아니다
_SENT2 = re.compile(r"(?:[^.!?]|(?<=\d)\.(?=\d))+[.!?]")
_HEAD2 = re.compile(r"^\s*(?:하이라이트 문장|문장|요약 코멘트|요약|코멘트|총평|답|출력)\s*[:：|]\s*")
_PYEON_TYPO = re.compile(r"(?<=편)합니다")
_SLOPE_HEAD = re.compile(r"^라운드별 공방 능력(?:\(VS\))? 변화(?:는|가|은|이)\s+(?=라운드)")
_LIST2 = re.compile(r"^\s*(?:[-*•]|\d{1,2}[.)](?!\d)|\[\d{1,2}\])\s*")


def clean_text(text: str, k_max: int) -> str | None:
    """light6.clean_sentences 와 같고, 소수점에서 문장을 자르지 않는다."""
    lines = []
    for raw in text.replace("\r", "").split("\n"):
        line = raw.strip().strip("*").strip()
        if not line or line.endswith((":", "：")):
            continue
        line = _HEAD2.sub("", _LIST2.sub("", line)).strip().strip("\"'`“”‘’「」*- ").strip()
        if line:
            lines.append(line)
    sents = [x.strip() for x in _SENT2.findall(" ".join(lines)) if x.strip()]
    return " ".join(sents[:k_max]) if sents else None


# v5.5: 1.8절 이전 이름도 꼬리표로 떼어 낸다 (모델이 예전 이름을 쓸 때)
_OLD_LABELS = {"strength_split": ("상대 강도별 승률 차",), "delta_comeback": ("역전 경향", "역전승 경향", "역전패 경향"),
               "session_vs_slope": ("라운드별 공방 능력",)}


def _label_name(k: str) -> str:
    return NAMES[k].split("(")[0].strip()


def _label_re(keys) -> re.Pattern:
    """문장 앞의 '지표 이름:' 꼬리표. 예: '상대 강도별 승률 차: …', 'Opener 성향: …'"""
    names = sorted({_label_name(k) for k in keys} | {a for k in keys for a in _OLD_LABELS.get(k, ())},
                   key=len, reverse=True)
    alt = "|".join(re.escape(n) for n in names)
    return re.compile(rf"^({alt})(?:\s*\([^()]*\))?(?:\s*변화)?\s*:\s*")


def _drop_label(sent: str, items) -> str:
    """'지표 이름: 나머지' → 나머지에 그 지표가 다시 나오면 꼬리표만 떼고, 없으면 '지표 이름은/는 나머지'."""
    m = _label_re([k for k, _, _ in items]).match(sent)
    if not m:
        return sent
    rest = sent[m.end():]
    k = next(k for k, _, _ in items if m.group(1) in (_label_name(k), *_OLD_LABELS.get(k, ())))
    if any(w in rest.lower() for w in STAT_WORDS[k]):
        return rest
    name = NAMES[k]
    return f"{name}{_topic(name)} {rest}"


def _split(text: str) -> list[str]:
    return [x.strip() for x in _SENT2.findall(text) if x.strip()]


def _starts_with_name(sent: str, k: str) -> bool:
    return sent.startswith(NAMES[k].split("(")[0].strip())   # 괄호 앞 이름이 전체 이름의 앞부분


_COMEBACK_HEAD = re.compile(r"^역전\s*경향(?:은|이|:)\s*(?=역전[승패])")
_COMEBACK_FRAME = re.compile(r"역전([승패]) 경향(?:이|은)\s*(?:[^.]*?\s)?역전(?!\1)[승패] 경향보다")
_OLD_WIN = re.compile(r"불리할 때 뒤집는 경향|불리한 경기를 뒤집는 (?:힘|경향)")
_OLD_LOSE = re.compile(r"유리하다가 뒤집히는 경향|유리한 경기를 내주는 경향")


def _old_comeback(t: str) -> str:
    """v5.5: 예전 역전 표현 → '역전승 경향' / '역전패 경향' (31번 1.8절)."""
    return _OLD_LOSE.sub(COMEBACK_LOSE, _OLD_WIN.sub(COMEBACK_WIN, t))


def _comeback_dir(text: str) -> int:
    """'역전승 경향이 역전패 경향보다 강합니다'의 방향. 예전 판별기(뒤집는/뒤집히는)에 맞춰 바꿔서 본다."""
    t = text.replace(COMEBACK_WIN, "뒤집는 경향").replace(COMEBACK_LOSE, "뒤집히는 경향")
    return _comeback_direction(t)


def tidy(text: str, items: list[tuple[str, float, str]], rng: random.Random | None = None) -> tuple[str, list[str]]:
    """(정리한 글, 한 일). 1) '이 플레이어는' 떼기 2) '지표 이름:' 꼬리표 떼기
    3) 상대와 비교하는 지표인데 비교 기준 없이 지표 이름으로 시작하는 문장 → 앞에 비교 표현을 붙인다(31번 1.3절).
    방향·숫자는 건드리지 않는다. 검사는 정리한 글로 한다."""
    rng = rng or random
    done = []
    t0 = _old_comeback(normalize_names(text))     # v5.5: 예전·영어 이름 → 31번 1.8절 이름
    if t0 != text:
        done.append("지표 이름 바꿈")
    t = _PLAYER_HEAD.sub(r"\1", t0)
    if t != t0:
        done.append("플레이어 주어 뗌")
    t2 = t.replace("블ock", "블록")             # v5.3 실측: 토큰이 깨져 '블ock당 공격량'
    if t2 != t:
        done.append("'블ock' 고침")
        t = t2
    t2 = re.sub(r"대비에서\s+", "대비 ", t)          # v5.4 실측: '대전 상대 평균 대비에서 …(APP)을 앞서는'
    t2 = re.sub(r"(블록당 공격력|보정 공격 효율)을 (앞서|밀리)", r"\1에서 \2", t2)
    if t2 != t:
        done.append("조사 고침")
        t = t2
    t2 = _PYEON_TYPO.sub("입니다", t)        # v5.3 실측: '앞서는 편합니다' (어미 오타)
    if t2 != t:
        done.append("'편합니다' 고침")
        t = t2
    out = []
    for sent in _split(t) or [t]:
        lab = _drop_label(sent, items)
        if lab != sent:
            done.append("꼬리표 뗌")
            sent = lab
            sent = _PLAYER_HEAD.sub("", sent)          # '지표: 이 플레이어는 …'
        for k, _, _ in items:   # v5.2: '블록당 공격량(APP)이 상대 대비 블록당 공격량(APP)에서 …' → 앞의 주어를 뗀다
            name = NAMES[k]
            m = re.match(rf"{re.escape(name)}\s*(?:이|가|은|는)\s+", sent)
            if m and name in sent[m.end():]:
                sent = sent[m.end():]
                done.append("주어 반복 뗌")
        m = _COMEBACK_HEAD.match(sent)   # v5.5: '역전 경향은 역전승 경향이 …' → 앞 주어 떼기
        if m:
            sent = sent[m.end():]
            done.append("주어 반복 뗌")
        m = _SLOPE_HEAD.match(sent)   # v5.3 실측: '라운드별 공방 능력(VS) 변화는 라운드가 진행될수록 …'
        if m:
            sent = sent[m.end():]
            done.append("주어 반복 뗌")
        # v5.6b: 절마다 (P1 규칙 3절·6절 2 "절마다 비교 기준") — ', '로 나눈 절이 지표 이름으로 시작하고 비교 기준이 없으면 붙인다
        clauses = re.split(r"(?<=[고며]),\s+|(?<=또한),\s+", sent)
        fixed = []
        for c in clauses:
            if not _BASIS.search(c):
                for k, v, z in items:
                    if k in COMPARE_KEYS and _starts_with_name(c, k):
                        c = f"{pick_phrase(state(k, v, z), rng)} {c}"
                        done.append(f"비교 기준 붙임({k})")
                        break
            fixed.append(c)
        sent = ", ".join(fixed)
        out.append(sent)
    t = " ".join(out)
    if len(items) == 1:
        t2 = _fix_ending(t, state(*items[0]))
        if t2 != t:
            done.append("끝말 고침")
            t = t2
        t2 = _add_sign(t, *items[0][:2])
        if t2 != t:
            done.append("괄호 값에 부호 붙임")
            t = t2
    else:
        t2 = _fix_splice(t)
        if t2 != t:
            done.append("문장 끝 뒤 쉼표 고침")
            t = t2
        t2 = _fix_frame(t)
        if t2 != t:
            done.append("첫머리 틀 맞춤")
            t = t2
    if len(items) == 3:
        t2 = _merge_three(t)
        if t2 != t:
            done.append("세 문장을 요약 틀로 묶음")
            t = t2
    if len(items) > 1:   # P1 규칙 6절: 요약에는 숫자를 쓰지 않는다 — 괄호 값 인용 "(+0.31)" 은 떼어 낸다 (뜻은 그대로)
        t2 = re.sub(r"\s*\(\s*[+\-−]?\d+(?:\.\d+)?\s*\)", "", t)
        if t2 != t:
            done.append("요약의 괄호 값 뗌")
            t = t2
    return t, done


_FRAME_HEAD = re.compile(r"^(?:이번 분석에서 눈에 띄는 점은|대전 기록에서 두드러지는 부분은)\s+")
_FRAME_TAIL = re.compile(r"(다는|라는)\s*(?:것을\s*눈에\s*띄게\s*합니다|것이\s*눈에\s*띕니다|점이\s*눈에\s*띕니다|것입니다)\.$")


def _fix_splice(text: str) -> str:
    """v5.6c (10/27 실측): '… Stride 성향이 뚜렷하게 옅습니다, 최근 … 밀리는 편입니다.' → '… 옅고, …' (끝말을 잇는 꼴로)"""
    for end, (conn, _) in _ENDINGS.items():
        text = text.replace(end + ", ", conn + ", ")
    return text


def _merge_three(text: str) -> str:
    """v5.6b (10/27 실측): 지표마다 한 문장씩 세 문장(개별 문장 이어 붙이기, 6절 ✗)을 대체 요약과 같은 틀로 묶는다.
    끝말이 _ENDINGS 표에 있을 때만 — '… 앞섭니다. … 짙습니다. … 작은 편입니다.' →
    '이번 분석에서 눈에 띄는 점은 … 앞선다는 것입니다. … 짙고, … 작은 편입니다.' (뜻은 그대로)"""
    sents = _split(text)
    if len(sents) != 3 or any(_FRAME_HEAD.match(x) or "것입니다" in x or "," in x for x in sents):
        return text
    ends = []
    for x in sents[:2]:
        body = x[:-1]
        end = next((e for e in _ENDINGS if body.endswith(e)), None)
        if not end:
            return text
        ends.append((body[:-len(end)], end))
    (b0, e0), (b1, e1) = ends
    return f"이번 분석에서 눈에 띄는 점은 {b0}{_ENDINGS[e0][1]} 것입니다. {b1}{_ENDINGS[e1][0]}, {sents[2]}"


def _fix_frame(text: str) -> str:
    """v5.6b (10/27 실측): 첫머리 틀(6절)의 앞뒤가 맞지 않는 요약 문장을 기계적으로 맞춘다 — 뜻은 그대로.
    '눈에 띄는 점은 … 없습니다.' → 틀을 뗀다 / 틀 없이 '… 떨어진다는 것을 눈에 띄게 합니다.' → '이번 분석에서 눈에 띄는 점은 … 떨어진다는 것입니다.'"""
    out = []
    for sent in _split(text) or [text]:
        head = _FRAME_HEAD.match(sent)
        tail = _FRAME_TAIL.search(sent)
        if head and not tail:
            sent = sent[head.end():]
        elif tail and not head and not re.search(r"점은|부분은", sent):
            sent = f"이번 분석에서 눈에 띄는 점은 {sent[:tail.start()]}{tail.group(1)} 것입니다."
        elif head and tail:
            sent = f"{sent[:tail.start()]}{tail.group(1)} 것입니다."
        out.append(sent)
    return " ".join(out)


def _add_sign(sent: str, k: str, v: float) -> str:
    """P1 규칙 1절 '부호 붙여': 괄호 안 값이 입력 값과 같은데 부호만 빠졌으면 붙인다 — '(0.31)' → '(+0.31)'."""
    shown = fmt_value(k, v)
    if shown[0] not in "+-":
        return sent
    return re.sub(rf"\(\s*{re.escape(shown[1:])}\s*\)", f"({shown})", sent)


# v5.3 실측: 보통인데 '밀립니다', 반대쪽인데 '조금 밀립니다'로 끝나는 문장 — 방향은 맞으니 끝말만 규칙대로 바꾼다
_END_MID = {"앞섭니다": "앞서는 편입니다", "밀립니다": "밀리는 편입니다", "짙습니다": "짙은 편입니다",
            "옅습니다": "옅은 편입니다", "큽니다": "큰 편입니다", "작습니다": "작은 편입니다",
            "강합니다": "강한 편입니다", "강해집니다": "강해지는 편입니다", "처집니다": "처지는 편입니다",
            "오릅니다": "오르는 편입니다", "떨어집니다": "떨어지는 편입니다"}
_END_WEAK = {"앞섭니다": "앞서지만 그 폭은 작은 편입니다", "밀립니다": "밀리지만 그 폭은 작은 편입니다",
             "짙습니다": "짙지만 그 폭은 작은 편입니다", "옅습니다": "옅지만 그 폭은 작은 편입니다",
             "큽니다": "큰 편이지만 그 폭은 크지 않습니다", "작습니다": "작은 편이지만 그 폭은 크지 않습니다",
             "강합니다": "강하지만 그 폭은 작은 편입니다", "오릅니다": "오르지만 그 폭은 작은 편입니다",
             "처집니다": "처지지만 그 폭은 작은 편입니다"}


def _fix_ending(sent: str, st: str) -> str:
    """마지막 낱말만 본다. 강조어('뚜렷하게','크게')가 있으면 고치지 않는다(검사에서 거부)."""
    if st.startswith("mid"):
        table = _END_MID
    elif st.startswith("weak") and not _SMALL_GAP.search(sent):
        table = _END_WEAK
    else:
        return sent
    if _MID_END.search(sent) and st.startswith("mid") or any(w in sent for w in _INTENSIFIERS):
        return sent
    m = re.search(r"([가-힣]+)\.$", sent)
    if not m or m.group(1) not in table:
        return sent
    return sent[:m.start(1)] + table[m.group(1)] + "."


# =====================================================================================
# 검사
# =====================================================================================
_MID_END = re.compile(r"편|흐름")                       # P1 규칙 4절 보통 "~하는 편 · ~하는 흐름"
_ADVICE = re.compile(r"해야\s*(?:합|하|할|한다|해요)|하는 것이 좋|필요가 있|필요합니다")   # 5절 처방 ✗
_OTHER_USERS2 = ("드물", "평균적인", "같은 티어", "비슷한 실력")                          # 5절 다른 유저와 비교 ✗
_PLAYSTYLE_KEYS = ("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds")
_LINK = re.compile(r"다만|반면|덕분에|때문에|그래서|따라서|그러므로|(?:^|(?<=[.!?]\s))(?:하지만|그러나|그런데)|(?<!비교)해서\s")
_OVERALL = re.compile(r"전반적|종합적|전체적으로|대체로|잘합니다|잘한다|못합니다")        # 6절 종합 평가 ✗
_OPENER_FRAME = re.compile(r"눈에 띄는 점은|두드러지는 부분은|특징으로 드러납니다")        # 6절 첫머리 틀 (강조어로 보지 않음)


def _outside_parens(text: str) -> str:
    return re.sub(r"\([^()]*\)", "", text)


def _common_issue(text: str, items: list[tuple[str, float, str]]) -> str | None:
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
    if "%" in text or "퍼센트" in text:
        return "백분율"
    if any(w in text for w in _FORBIDDEN):
        return "금지어"
    if _TYPECAST.search(text):
        return "유형 규정"
    if _RECENT.search(text):
        return "최근 흐름"
    if any(w in text for w in _OTHER_USERS) or any(w in text for w in _OTHER_USERS2):
        return "다른 유저와 비교"
    if _ADVICE.search(text):
        return "처방(~해야 합니다)"
    if any(k in _PLAYSTYLE_KEYS for k, _, _ in items) and re.search(r"가장|특히", text):
        return "플레이스타일끼리 비교(가장·특히)"
    if re.search(r"보통|범위|영역|사분위|벗어난", text):
        return "판정 정보 노출"
    if "상대적" in text:
        return "비교 기준 흐림(상대적)"
    if "플레이어" in text or "레이어" in text:
        return "플레이어 주어"
    if re.search(r"[+\-−]?\d+(?:\.\d+)?\s*(?:는|은|이므로|이라|으로|로|이며|이고)(?=[\s,])", text):   # v5.4 실측: '역전 경향의 +0.12는 …', '-0.10이므로'
        return "값 풀이"
    if re.search(r"(?:의|인)\s*값|값은|값이", text):      # '역전 경향의 값 -0.10은 …' — 값을 풀어 쓰지 않는다
        return "값 풀이"
    if re.search(r"수비적|공격적", text):                # '수비 비중이 작고, 수비적인 편' — 유형 규정·반대 해석
        return "유형 규정(수비적·공격적)"
    if "견해" in text:                                   # '매 경기 상대와 견해하면' (견줘 보면 오타)
        return "비교 표현 오타"
    if re.search(r"[+-]\s?\d+(?:\.\d+)?\s*(?:로|만큼)?\s*(?:더\s*)?(?:높|낮|증가|감소|많|적)", text):
        return "부호 숫자로 비교"                         # '+0.08 높습니다' — 헤비 v4.3 과 같은 규칙
    if _weak_first(text):
        return "방향 반대(강한·약한 상대 순서)"
    if re.search(r"뺀 값|0과 1 사이|정규화", text):
        return "정의 풀이"
    if re.search(r"(?<![A-Za-z가-힣])TR(?![A-Za-z.])|티어|랭크|레이팅", text):
        return "TR·티어 언급"
    if "승률" in text and not any(k == "strength_split" for k, _, _ in items):
        return "입력에 없는 승률"
    # P1 규칙 1절: 입력 값을 그대로(다시 반올림 ✗) 괄호에. 요약은 숫자 ✗ (6절)
    if len(items) > 1:
        if _NUM.search(text):
            return "요약에 숫자"
    else:
        k, v, _ = items[0]
        allowed = {round(abs(float(fmt_value(k, v))), 6)}
        for m in _NUM.findall(text):
            if round(float(m), 6) not in allowed:
                return f"입력에 없는 숫자(다시 반올림 포함): {m}"
        if _NUM.search(_outside_parens(text)):
            return "괄호 밖의 숫자"
    for k, _, _ in items:
        if not any(w in text.lower() for w in STAT_WORDS[k]):
            return f"지표 빠짐: {NAMES[k]}"
    given = {k for k, _, _ in items}   # 1절: 개별은 그 지표 하나만, 요약은 받은 지표만
    for k2, words in STAT_WORDS.items():
        words = [w for w in words if w not in ("app", "weighted", "cheese", "inf ds", "inf_ds")]   # 'Weighted APP' 안의 'app'
        if k2 not in given and any(w in text.lower() for w in words):
            return f"입력에 없는 지표: {NAMES[k2]}"
    return None


def _weak_first(text: str) -> bool:
    """문장마다 '약한 상대'가 '강한 상대'보다 먼저 나오면 기준이 뒤바뀐 것 (문장 틀은 늘 강한 상대가 먼저).
    v5.3 실측: '약한 상대 구간에서 오히려 강한 상대 구간보다 승률이 높습니다' 가 기본 코멘트에서 통과했음."""
    for sent in _split(text) or [text]:
        i, j = sent.find("약한 상대"), sent.find("강한 상대")
        if 0 <= i and 0 <= j and i < j:
            return True
    return False


_THICK = re.compile(r"진하|진한|진합")          # '짙다'와 같은 뜻 (light6 방향 낱말에 없음)
_SPEED = re.compile(r"빠르|빠른|빠르게|느리|느린|느슨")  # v5.3 실측: 'Opener 성향이 빠르게 변합니다', '작고 느린 편'
_SMALL_GAP = re.compile(r"폭|크지\s*(?:는\s*)?않|작은 편")


def check_sentence(text: str, k: str, v: float, zone: str) -> str | None:
    """개별 코멘트 검사. 문제가 있으면 이유, 없으면 None."""
    why = _check_sentence_core(text, k, v, zone)
    if why:
        return why
    # v5.3 규칙 (31번 2.2절 A안): 보통 → '~하는 편', 반대쪽 → '폭이 작다/잘 버틴다' — 방향 검사를 통과한 뒤에 본다
    st = state(k, v, zone)
    if k == "delta_comeback" and st != "zero" and not _COMEBACK_FRAME.search(_old_comeback(text)):
        return "역전 문장 틀 아님"   # v5.4 실측: '역전 경향이 불리할 때 뒤집는 경향과 유리하다가 뒤집히는 경향보다 강한 편' 통과했음
    if st.startswith("mid") and not _MID_END.search(text):
        return "보통인데 '~하는 편·흐름' 없음"
    if st.startswith("weak") and not (k == "strength_split" and st == "weak-") and not _SMALL_GAP.search(text):
        return "반대쪽인데 '폭이 작다' 없음"
    return None


def _check_sentence_core(text: str, k: str, v: float, zone: str) -> str | None:
    why = _common_issue(text, [(k, v, zone)])
    if why:
        return why
    st = state(k, v, zone)
    if text.count(NAMES[k].split("(")[0].strip()) > 1:
        return "지표 이름 반복"
    if k in COMPARE_KEYS and not _BASIS.search(text):
        return "비교 기준 누락"
    if k in ("strength_split", "delta_comeback") and re.search(r"상대\s*(보다|대비)", text):
        return "비교 기준 잘못(상대)"
    if k == "session_vs_slope" and "상대" in text:
        return "비교 기준 잘못(상대)"
    if k in TENDENCY_KEYS and (any(w in text for w in _RANK_WORDS) or re.search(r"잘하|못하|잘한|못한|강점|약점", text)):
        return "성향에 우열 서술어"
    if k in TENDENCY_KEYS and _SPEED.search(text):
        return "성향을 속도로 풀이"
    # P1 규칙 2절 지표 사전의 서술어 ('그 폭은 작은 편'은 강도 표현이라 빼고 본다)
    pred = re.sub(r"폭은?\s*(?:작은 편|크지\s*않)", "", text)
    if k in _PLAYSTYLE_KEYS and re.search(r"작고|크고|큰 편|작은 편|큽니|작습|강하|강합|강한|약한|약하|약합|높|낮", pred):
        return "성향을 크기·세기로 풀이"   # v5.4 실측: 'Opener 성향이 작고 약한 편' — 플레이스타일은 짙다/옅다
    if k == "delta_cheese_index" and re.search(r"작고|크고|큰 편|작은 편|큽니|작습|높|낮", pred):
        return "가비지 처리 성향을 크기로·높낮이로 풀이"   # 짙다·옅다·강하다·약하다 (v5.6c 실측: '높은 편입니다')
    if k == "delta_vs_apm" and re.search(r"짙|옅|강하|강합|약하|약합", pred):
        return "수비 비중을 크다·작다 밖으로 풀이"
    if k in RANK_KEYS and re.search(r"높|낮|강하|강합|강한|강해|약하|약합|약한", text):
        return "우열 지표에 높다·강하다 (앞선다·밀린다만)"
    soft = st in SOFT_STATES
    if soft:
        hit = [w for w in _INTENSIFIERS if w in _OPENER_FRAME.sub("", text)]
        # 허용: "크게 흔들리지/떨어지지 않습니다"(잘 버팀), "뚜렷한 추세가 없습니다"(0)
        hit = [w for w in hit if not re.search(w + r"\S*\s*(흔들리지|떨어지지)\s*않", text)]
        if k == "session_vs_slope" and st == "zero":
            hit = [w for w in hit if not re.search(w + r"\S*\s*(추세|변화)\S*\s*없", text)]
        if hit:
            return f"차이가 크지 않은데 강조: {hit}"
        if st.startswith("mid") and re.search(r"같은 수준|비슷|차이가 없|차이는 없", text):
            return "값이 0이 아닌데 같다고 씀"
    elif any(w in text for w in _VAGUE):
        return "차이가 큰데 흐림"
    if st == "zero":
        if k == "session_vs_slope" and "추세" not in text and "변화" not in text:
            return "추세 없음을 쓰지 않음"
        return None
    if k == "strength_split":
        hold_words = r"흔들리지\s*않|떨어지지\s*않|버티|버팁|크지\s*않|조금\s*낮|약간\s*낮|폭은?\s*작"
        if st in ("high", "weak+", "mid+"):                # 양수 — 강한 상대 구간이 오히려 높음
            return None if "높" in text else "방향 없음"
        if "오히려 높" in text:
            return "방향 반대"
        if st == "weak-":
            return None if re.search(hold_words, text) else "방향 없음"
        if re.search(r"흔들리지\s*않|떨어지지\s*않|버티|버팁", text):
            return "방향 반대"
        return None if re.search(r"낮|떨어", text) else "방향 없음"
    want = 1 if st.endswith("+") or st == "high" else -1
    d = _comeback_dir(text) if k == "delta_comeback" else _first_direction(text)
    if d == 0 and k in TENDENCY_KEYS and _THICK.search(text):
        d = 1
    if d == 0:
        return "방향 없음"           # v5.3: 보통·반대쪽도 방향은 부호대로 써야 한다 ('역전 경향은 +0.02입니다' 통과했음)
    if d != want:
        return f"방향 반대 ({fmt_value(k, v)})"
    return None


def check_summary(text: str, items: list[tuple[str, float, str]]) -> str | None:
    """기본 코멘트 검사. 지표별 방향은 문장 구조가 다양해 보지 않고(개별 코멘트에서 봄), 공통·비교 기준·강조만 본다."""
    why = _common_issue(text, items)
    if why:
        return why
    keys = [k for k, _, _ in items]
    if any(k in COMPARE_KEYS for k in keys) and not _BASIS.search(text):
        return "비교 기준 누락"
    for sent in _split(text):       # v5.1: 문장마다 — 상대와 비교하는 지표를 말하는 문장에는 비교 기준 (31번 1.3절)
        named = [k for k in keys if k in COMPARE_KEYS and any(w in sent.lower() for w in STAT_WORDS[k])]
        if named and not _BASIS.search(sent):
            return f"비교 기준 누락(문장: {NAMES[named[0]]})"
    if "session_vs_slope" in keys and len(keys) == 1 and "상대" in text:
        return "비교 기준 잘못(상대)"
    sents = _split(text)
    for sent in sents:              # v5.6b (10/27 실측): 첫머리 틀과 끝말이 맞지 않는 문장
        framed = bool(re.search(r"점은|부분은", sent))
        if framed and not re.search(r"(?:다는|라는|하다는)\s*(?:것|점)입니다\.?$", sent):
            return "첫머리 틀과 끝말이 맞지 않음('…점은 … 것입니다')"   # '눈에 띄는 점은 … 추세가 없습니다.'
        if not framed and re.search(r"(?:다는|라는)\s*것(?:입니다|을|이)", sent):
            return "첫머리 틀 없이 '…다는 것'"                         # '… 떨어진다는 것을 눈에 띄게 합니다.'
    short = {k: NAMES[k].split("(")[0].strip() for k in keys}
    for k, n in short.items():      # 지표 이름은 한 번씩 (역전은 두 이름이 한 틀)
        if k != "delta_comeback" and text.count(n) > 1:
            return f"지표 이름 반복: {n}"
    if (len(keys) == 3 and len(sents) >= 3 and not any(_OPENER_FRAME.search(x) or "주목" in x for x in sents)
            and all(sum(any(w in x.lower() for w in STAT_WORDS[k]) for k in keys) == 1 for x in sents)):
        return "개별 문장 이어 붙이기"   # P1 규칙 6절: 지표마다 한 문장씩 세 문장
    if _LINK.search(text):          # P1 규칙 6절 3: 지표 사이의 대비·인과 연결 ✗
        return "대비·인과 연결어"
    if _OVERALL.search(text):
        return "종합 평가"
    names = [NAMES[k] for k in keys if k not in ("strength_split", "delta_comeback", "session_vs_slope")]
    for a in names:                 # 6절 2: 두 지표를 한 주어로 묶지 않는다
        for b in names:
            if a != b and re.search(re.escape(a) + r"\s*(?:과|와|및|,|·)\s*" + re.escape(b), text):
                return "두 지표를 한 주어로 묶음"
    if all(state(k, v, z) in SOFT_STATES for k, v, z in items):
        hit = [w for w in _INTENSIFIERS if w in _OPENER_FRAME.sub("", text)]
        hit = [w for w in hit if not re.search(w + r"\S*\s*((흔들리지|떨어지지)\s*않|추세가?\s*없)", text)]
        if hit:
            return f"차이가 크지 않은데 강조: {hit}"
    only_tendency = all(k in TENDENCY_KEYS for k in keys)
    if only_tendency and any(w in text for w in _RANK_WORDS):
        return "성향에 우열 서술어"
    return None


def parse_sentence(text: str, k: str, v: float, zone: str,
                   rng: random.Random | None = None) -> tuple[str | None, str | None]:
    s = clean_text(text, 1)
    if not s:
        return None, "완성된 문장 없음"
    s, _ = tidy(s, [(k, v, zone)], rng)
    why = check_sentence(s, k, v, zone)
    return (None, why) if why else (s, None)


# v5.6c (10/27 실측): 입력 줄을 그대로 옮긴 꼴 '- 이름: 값 +0.40, 방향: 문장' → 방향 문장만 (뜻은 입력 방향 그대로)
_INPUT_LINE = re.compile(r"^\s*(?:[-*•]\s*)?[^:\n]{1,30}:\s*값\s*[+\-−]?\d+(?:\.\d+)?\s*,\s*방향\s*:\s*(.+?)\s*$")


def _salvage_lines(text: str) -> str:
    """요약 출력의 줄 단위 손질 (v5.6c 실측): 입력 줄 꼴은 방향 문장만 남기고, '…니다'로 끝나는데 마침표가 없는 줄에 마침표."""
    out = []
    for line in text.replace("\r", "").split("\n"):
        m = _INPUT_LINE.match(line)
        if m:
            line = m.group(1)
        if re.search(r"니다$", line.strip()):
            line = line.rstrip() + "."
        out.append(line)
    return "\n".join(out)


def parse_summary(text: str, items: list[tuple[str, float, str]],
                  rng: random.Random | None = None) -> tuple[str | None, str | None]:
    s = clean_text(_salvage_lines(text), 3)
    if not s:
        return None, "완성된 문장 없음"
    s, _ = tidy(s, items, rng)
    why = check_summary(s, items)
    return (None, why) if why else (s, None)
