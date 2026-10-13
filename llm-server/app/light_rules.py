"""하이라이트 선정 규칙 (2026-10-13 받은 규칙) — 서버가 하는 계산과 사후 검사.

규칙
  1. null 은 제외
  2. 보통 범위(유저 전체 Q1~Q3) 밖이면 높은 편/낮은 편, 안이면 보통.
     높은/낮은 편을 먼저, 그중 더 멀리 벗어난 것을 먼저
  3. 같은 그룹에서 1개만 (플레이스타일 4개 / 공격 2개 / 수비 2개 / 나머지 3개는 각각 별개 그룹)
  4. 부족하면 3번을 풀고, 그래도 부족하면 '보통' 중 범위 끝에 가까운 것
  5. 두드러진 순서로 나열

서버가 하는 일 (모델에는 숫자를 주지 않는다)
  - 분류와 벗어난 정도 계산. 지표마다 단위가 달라 범위 폭(Q3−Q1)으로 나눈다.
      범위 위  (v−Q3)/(Q3−Q1),  범위 아래 (Q1−v)/(Q3−Q1),  범위 안 = 가까운 끝까지 거리/(Q3−Q1)
  - 후보를 두드러진 순서로 정렬하고 그룹·라벨을 붙인다 → 프롬프트
  - 모델이 고른 뒤 규칙을 어긴 선택만 뺀다 (check_pick). 사전 선별이 아니라 사후 검사다
    (하이라이트 지표 설계 2026-09-30, v1.2 3절: 선정은 LLM, 결정론적 사전 선별 기각).
  - expected_picks: 규칙을 그대로 따랐을 때의 답. 측정용(규칙 정답 일치율)이고 응답에는 쓰지 않는다.

보통 범위 값은 config/light_ranges.json (LLM_LIGHT_RANGES 로 다른 파일 지정 가능). 코드에 숫자를 쓰지 않는다.
"""
import json
import math
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from app.schemas import CANDIDATE_KEYS

DEFAULT_RANGES_PATH = Path(__file__).resolve().parent.parent / "config" / "light_ranges.json"

# 그룹 (선정 규칙 3번). 단독 3개는 서로 다른 그룹이므로 이름을 따로 둔다.
LIGHT_GROUPS: dict[str, str] = {
    "delta_opener": "플레이스타일",
    "delta_plonk": "플레이스타일",
    "delta_stride": "플레이스타일",
    "delta_inf_ds": "플레이스타일",
    "delta_app": "공격",
    "delta_weighted_app": "공격",
    "delta_vs_apm": "수비",
    "delta_cheese_index": "수비",
    "strength_split": "상대 강도",
    "delta_comeback": "역전",
    "session_vs_slope": "라운드 흐름",
}

HIGH, LOW, NORMAL, UNKNOWN = "high", "low", "normal", "unknown"


@dataclass(frozen=True)
class RangeConfig:
    ranges: dict[str, tuple[float, float] | None]
    big_threshold: float
    source: str


@dataclass(frozen=True)
class Standing:
    """후보 하나의 분류 결과."""
    key: str
    value: float
    group: str
    level: str          # high | low | normal | unknown(범위 없음)
    dist: float         # 범위 밖: 벗어난 정도 / 범위 안: 가까운 끝까지 거리 / unknown: inf
    label: str          # 프롬프트에 쓰는 라벨 (숫자 없음)

    @property
    def outside(self) -> bool:
        return self.level in (HIGH, LOW)


def load_ranges(path: str | os.PathLike | None = None) -> RangeConfig:
    p = Path(path or os.getenv("LLM_LIGHT_RANGES") or DEFAULT_RANGES_PATH)
    return _load_ranges_cached(str(p.resolve()), p.stat().st_mtime)


@lru_cache(maxsize=4)
def _load_ranges_cached(path: str, _mtime: float) -> RangeConfig:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    ranges: dict[str, tuple[float, float] | None] = {}
    for key in CANDIDATE_KEYS:
        r = raw["ranges"].get(key)
        if r is None:
            ranges[key] = None
            continue
        q1, q3 = float(r[0]), float(r[1])
        if not q3 > q1:
            raise ValueError(f"{path}: {key} 범위는 Q1 < Q3 여야 합니다: {r}")
        ranges[key] = (q1, q3)
    return RangeConfig(ranges, float(raw.get("big_threshold", 0.5)), str(raw.get("source", "")))


def classify(key: str, value: float, cfg: RangeConfig) -> Standing:
    group = LIGHT_GROUPS[key]
    r = cfg.ranges.get(key)
    if r is None:
        return Standing(key, value, group, UNKNOWN, math.inf, "기준 없음")
    q1, q3 = r
    width = q3 - q1
    if value > q3:
        d = (value - q3) / width
        return Standing(key, value, group, HIGH, d, f"높은 편, {_size(d, cfg)}")
    if value < q1:
        d = (q1 - value) / width
        return Standing(key, value, group, LOW, d, f"낮은 편, {_size(d, cfg)}")
    d = min(value - q1, q3 - value) / width
    return Standing(key, value, group, NORMAL, d, "보통")


def _size(d: float, cfg: RangeConfig) -> str:
    return "많이 벗어남" if d >= cfg.big_threshold else "조금 벗어남"


def rank(available: dict[str, float], cfg: RangeConfig) -> list[Standing]:
    """두드러진 순서: 범위 밖(더 멀리 벗어난 것 먼저) → 보통(범위 끝에 가까운 것 먼저) → 기준 없음.
    같으면 문서(v1.2 5.1절) 순서."""
    order = {k: i for i, k in enumerate(CANDIDATE_KEYS)}
    items = [classify(k, v, cfg) for k, v in available.items()]

    def sort_key(s: Standing):
        if s.outside:
            return (0, -s.dist, order[s.key])
        if s.level == NORMAL:
            return (1, s.dist, order[s.key])
        return (2, 0.0, order[s.key])

    return sorted(items, key=sort_key)


def target_count(ranked: list[Standing]) -> int:
    return min(3, len(ranked))


def expected_picks(ranked: list[Standing], n: int = 3) -> list[str]:
    """규칙 1~5 를 그대로 따랐을 때의 답 (측정용).

    4번의 '보통' 단계에는 그룹 조건을 적용하지 않는다 (규칙 문장 그대로: 3번을 푼 다음 단계이므로).
    """
    outside = [s for s in ranked if s.outside]
    picks: list[Standing] = []
    for s in outside:                                   # 2·3: 벗어난 것, 그룹마다 1개
        if len(picks) < n and s.group not in {p.group for p in picks}:
            picks.append(s)
    for s in outside:                                   # 4: 3번을 풀어 같은 그룹에서
        if len(picks) < n and s not in picks:
            picks.append(s)
    for s in ranked:                                    # 4: 보통(범위 끝에 가까운 것) → 기준 없음
        if len(picks) < n and not s.outside:
            picks.append(s)
    pos = {s.key: i for i, s in enumerate(ranked)}
    return [s.key for s in sorted(picks, key=lambda s: pos[s.key])]   # 5: 두드러진 순서


def check_pick(accepted: list[Standing], cand: Standing, ranked: list[Standing]) -> str | None:
    """이미 받아들인 선택(accepted) 다음에 cand 를 고르는 것이 규칙 위반이면 이유, 아니면 None.

    빼는 경우는 분명한 위반 두 가지뿐이다. '더 멀리 벗어난 것 먼저'를 어긴 선택은 빼지 않고
    측정에서 규칙 정답 일치율로만 본다 (모델이 고를 여지를 남김).
      - 보통(또는 기준 없음)을 골랐는데 아직 고르지 않은 높은/낮은 편 후보가 있음
      - 이미 고른 그룹을 또 골랐는데 다른 그룹의 높은/낮은 편 후보가 남아 있음
    """
    taken = {s.key for s in accepted} | {cand.key}
    left_outside = [s for s in ranked if s.outside and s.key not in taken]
    if not cand.outside and left_outside:
        return "보통인데 높은/낮은 편 후보가 남음"
    if cand.outside:
        groups = {s.group for s in accepted}
        if cand.group in groups and any(s.group not in groups for s in left_outside):
            return f"같은 그룹({cand.group}) 두 번 — 다른 그룹 후보가 남음"
    return None


def apply_rules(keys: list[str], ranked: list[Standing], n: int = 3) -> tuple[list[str], list[str]]:
    """모델이 고른 순서대로 검사해 (받아들인 키, 뺀 이유) 를 돌려준다."""
    by_key = {s.key: s for s in ranked}
    accepted: list[Standing] = []
    dropped: list[str] = []
    for k in keys:
        if len(accepted) >= n:
            dropped.append(f"{k}: 3개 초과")
            continue
        if (why := check_pick(accepted, by_key[k], ranked)):
            dropped.append(f"{k}: {why}")
            continue
        accepted.append(by_key[k])
    return [s.key for s in accepted], dropped
