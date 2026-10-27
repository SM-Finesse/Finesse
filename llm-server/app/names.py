"""AI 코멘트에 쓰는 지표 이름 — 31번 「코멘트 작성 가이드라인」 1.8절 (PM 확정, 10/26).

헤비·라이트(개별·기본) 코멘트 모두 이 이름만 쓴다. 문장 틀·프롬프트·검사가 여기서 이름을 가져간다.
모델이 예전 이름(블록당 공격량(APP), Weighted APP, Inf DS, (VS/APM) 등)을 쓰면 normalize_names 가 새 이름으로 바꾼다.

※ delta_inf_ds: 「P1 프롬프트에 넣을 규칙 — 최종(최소안)」(10/26 저녁, 정본 claude/31) 2절 지표 사전의 "Infinite Downstack 성향".
  (낮에는 PM 표의 "Downtacking"을 Downstacking 으로 고쳐 썼음 — 모델이 예전 표기를 쓰면 normalize_names 가 바꾼다)
※ 헤비 역전승 퍼포먼스 챕터의 비율 이름(역전승률·역전 허용률)은 1.8절에 없어 그대로 둔다.
"""

from __future__ import annotations

import re

COMMENT_NAMES: dict[str, str] = {
    "delta_opener": "Opener 성향",
    "delta_plonk": "Plonk 성향",
    "delta_stride": "Stride 성향",
    "delta_inf_ds": "Infinite Downstack 성향",
    "delta_app": "블록당 공격력",
    "delta_weighted_app": "보정 공격 효율",
    "delta_vs_apm": "수비 비중",
    "delta_cheese_index": "가비지 처리 성향",
    "strength_split": "상대 강도별 승률",       # 문장은 "강한 상대 구간에서는 약한 상대 구간보다 승률이 …"
    "delta_comeback": "역전승·역전패 경향",     # 문장은 "역전승 경향이 역전패 경향보다 …"
    "session_vs_slope": "라운드별 공방 능력 변화",  # 문장은 "라운드가 진행될수록 공방 능력이 …"
}

# 플레이스타일 4개 — 헤비 플레이스타일 챕터는 "Opener, Plonk 성향은 …"처럼 '성향'을 한 번만 붙인다
PLAYSTYLE_SHORT: dict[str, str] = {
    "delta_opener": "Opener", "delta_plonk": "Plonk", "delta_stride": "Stride",
    "delta_inf_ds": "Infinite Downstack",
}

# 역전 경향 (light delta_comeback)
COMEBACK_WIN = "역전승 경향"
COMEBACK_LOSE = "역전패 경향"

# 문장에 남아도 되는 영어 낱말 (이름 속 영어)
LATIN_NAME_WORDS = {"opener", "plonk", "stride", "infinite", "downstack"}


def _has_batchim(word: str) -> bool:
    for ch in reversed(word):
        if "가" <= ch <= "힣":
            return (ord(ch) - 0xAC00) % 28 != 0
        if ch.isalpha():          # 영어로 끝남 — 이 목록의 영어 이름(Opener 등)은 뒤에 '성향'이 붙어 쓰이지 않음
            return False
    return False


_PARTICLES = {("은", "는"): "topic", ("이", "가"): "subj", ("을", "를"): "obj", ("과", "와"): "and"}


def _fix_particle(name: str, particle: str) -> str:
    if not particle:
        return ""
    if particle in ("으로", "로"):
        last = name[-1]
        rieul = "가" <= last <= "힣" and (ord(last) - 0xAC00) % 28 == 8
        return "로" if (not _has_batchim(name) or rieul) else "으로"
    for with_b, without_b in _PARTICLES:
        if particle in (with_b, without_b):
            return with_b if _has_batchim(name) else without_b
    return particle


_P = r"(으로|로|은|는|이|가|을|를|과|와)?"
# (예전 이름 정규식, 새 이름). 긴 것부터 — 괄호 붙은 꼴을 먼저 바꿔 "보정 공격 효율(보정 공격 효율)"이 생기지 않게
_RULES: list[tuple[re.Pattern, str]] = [
    (re.compile(r"블록당\s*공격(?:량|력)\s*\(\s*APP\s*\)" + _P), "블록당 공격력"),
    (re.compile(r"보정\s*공격\s*효율\s*\(\s*Weighted\s*APP\s*\)" + _P), "보정 공격 효율"),
    (re.compile(r"Weighted\s*APP(?:\s*\(\s*보정\s*공격\s*효율\s*\))?" + _P), "보정 공격 효율"),
    (re.compile(r"수비\s*비중\s*\(\s*VS\s*/\s*APM\s*\)" + _P), "수비 비중"),
    (re.compile(r"가비지\s*처리\s*성향\s*\(\s*Cheese\s*Index\s*\)" + _P), "가비지 처리 성향"),
    (re.compile(r"Cheese\s*Index(?:\s*\(\s*가비지\s*처리\s*성향\s*\))?" + _P), "가비지 처리 성향"),
    (re.compile(r"공방\s*능력\s*\(\s*VS\s*\)" + _P), "공방 능력"),
    (re.compile(r"블록당\s*공격량" + _P), "블록당 공격력"),
    (re.compile(r"(?i:inf(?:inite)?[\s_]*down[\s_]*s?t?ack(?:ing|s)?)|Inf(?:inite)?[\s_]*DS(?![A-Za-z])"), "Infinite Downstack"),   # v5.6c: 소문자 'infinite downstack'도
    (re.compile(r"(?<![A-Za-z])(?i:opener)(?![A-Za-z])"), "Opener"),   # v5.6d (10/27 실측): '-opener(-0.05) 성향' — 소문자
    (re.compile(r"(?<![A-Za-z])(?i:plonk)(?![A-Za-z])"), "Plonk"),
    (re.compile(r"(?<![A-Za-z])(?i:stride)(?![A-Za-z])"), "Stride"),
    (re.compile(r"(?<![A-Za-z/])VS\s*/\s*APM(?![A-Za-z])" + _P), "수비 비중"),
    (re.compile(r"(?<![A-Za-z/])APP(?![A-Za-z])" + _P), "블록당 공격력"),
    (re.compile(r"(?<![A-Za-z/])VS(?![A-Za-z/])" + _P), "공방 능력"),
]


def normalize_names(text: str) -> str:
    """예전·영어 지표 이름을 1.8절 이름으로 바꾸고, 바로 뒤 조사(은/는·이/가 …)를 받침에 맞춘다."""
    for pat, new in _RULES:
        if pat.groups:
            text = pat.sub(lambda m, new=new: new + _fix_particle(new, m.group(m.lastindex or 0) if m.lastindex else ""),
                           text)
        else:
            text = pat.sub(new, text)
    return text
