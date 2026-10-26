"""하이라이트 선정 규칙 (app/light_rules.py) 테스트."""
import json
import math
from pathlib import Path

import pytest

from app.light_rules import (
    RangeConfig,
    apply_rules,
    check_pick,
    classify,
    expected_picks,
    load_ranges,
    rank,
)
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLES = Path(__file__).parent / "samples"


def _cfg(**over) -> RangeConfig:
    base = load_ranges()
    ranges = dict(base.ranges)
    ranges.update(over)
    return RangeConfig(ranges, base.big_threshold, "test")


def _available(name: str) -> dict[str, float]:
    return LightRequest.model_validate(json.loads((SAMPLES / name).read_text(encoding="utf-8"))).available_stats()


def test_설정_파일에_11개_키가_모두_있음():
    cfg = load_ranges()
    assert set(cfg.ranges) == set(CANDIDATE_KEYS)
    assert cfg.ranges["strength_split"] is None   # 문서(v1.2 5.1)에 범위 없음 → 임시로 비워 둠


@pytest.mark.parametrize("key, v, level, dist, label", [
    ("delta_opener", 0.9, "high", 0.4, "높은 편, 조금 벗어남"),        # (0.9-0.5)/1.0
    ("delta_weighted_app", -1.34, "low", 1.04 / 0.6, "낮은 편, 많이 벗어남"),
    ("delta_cheese_index", 45, "high", 15 / 60, "높은 편, 조금 벗어남"),  # 단위가 달라도 범위 폭으로 나눠 비교
    ("delta_plonk", -0.22, "normal", 0.28, "보통"),                   # 가까운 끝(-0.5)까지 0.28
    ("delta_app", 0.3, "normal", 0.0, "보통"),                        # 경계값은 범위 안
    ("strength_split", -0.3, "unknown", math.inf, "기준 없음"),
])
def test_분류와_벗어난_정도(key, v, level, dist, label):
    s = classify(key, v, load_ranges())
    assert (s.level, s.label) == (level, label)
    assert s.dist == pytest.approx(dist)


def test_정렬_범위밖_먼_순서_다음_보통_끝에_가까운_순서():
    ranked = rank(_available("light_spread.json"), load_ranges())
    keys = [s.key for s in ranked]
    assert keys[:7] == ["delta_weighted_app", "delta_opener", "session_vs_slope", "delta_app",
                        "delta_cheese_index", "delta_inf_ds", "delta_vs_apm"]
    assert keys[-1] == "strength_split"


@pytest.mark.parametrize("sample, expected", [
    ("light_spread.json", ["delta_weighted_app", "delta_opener", "session_vs_slope"]),
    ("light_testuser.json", ["delta_weighted_app", "delta_plonk", "delta_comeback"]),
    ("light_pos.json", ["delta_weighted_app", "delta_comeback", "delta_plonk"]),
])
def test_규칙_정답(sample, expected):
    assert expected_picks(rank(_available(sample), load_ranges())) == expected


def test_다른_그룹이_모자라면_같은_그룹을_보통보다_먼저():
    # 범위 밖: 공격 2개뿐. 규칙 4: 3번을 먼저 풀고(같은 그룹), 그래도 모자라면 보통
    avail = {"delta_app": 0.9, "delta_weighted_app": 0.6, "delta_opener": 0.1, "delta_plonk": 0.45}
    assert expected_picks(rank(avail, load_ranges())) == ["delta_app", "delta_weighted_app", "delta_plonk"]


def test_후보가_3개_미만이면_있는_만큼():
    avail = {"delta_app": 0.9, "delta_weighted_app": 0.6}
    ranked = rank(avail, load_ranges())
    assert expected_picks(ranked) == ["delta_app", "delta_weighted_app"]
    assert apply_rules(["delta_weighted_app", "delta_app"], ranked) == (["delta_weighted_app", "delta_app"], [])


def test_사후_검사_같은_그룹과_보통():
    ranked = rank(_available("light_spread.json"), load_ranges())
    st = {s.key: s for s in ranked}
    # 공격 2개 연속 → 둘째를 뺀다 (다른 그룹의 벗어난 후보가 남아 있음)
    assert "같은 그룹" in check_pick([st["delta_weighted_app"]], st["delta_app"], ranked)
    # 벗어난 후보가 남았는데 보통을 고름 → 뺀다
    assert "보통" in check_pick([], st["delta_plonk"], ranked)
    # '더 멀리 벗어난 것 먼저'를 어긴 것만으로는 빼지 않는다 (측정에서만 봄)
    assert check_pick([], st["delta_vs_apm"], ranked) is None
    accepted, dropped = apply_rules(["delta_app", "delta_weighted_app", "delta_plonk", "delta_inf_ds"], ranked)
    assert accepted == ["delta_app", "delta_inf_ds"] and len(dropped) == 2


def test_보통은_범위_밖_후보_자리만_남기면_허용():
    ranked = rank(_available("light_testuser.json"), load_ranges())   # 범위 밖은 weighted_app 하나
    # 보통을 먼저 골라도 weighted_app 자리가 남아 있으면 받는다
    assert apply_rules(["delta_opener", "delta_weighted_app", "delta_stride"], ranked) == (
        ["delta_opener", "delta_weighted_app", "delta_stride"], [])
    # 10/13 11번 실측 "3, 10, 6": 보통 3개 → 마지막 하나만 뺀다 (예전에는 셋 다 빠져 0개)
    accepted, dropped = apply_rules(["delta_comeback", "session_vs_slope", "delta_app"], ranked)
    assert accepted == ["delta_comeback", "session_vs_slope"] and "자리" in dropped[0]


def test_같은_그룹은_다른_그룹으로_못_채울_때만():
    # 범위 밖: 공격 2개 + 플레이스타일 1개 → weighted, app, opener 순서로 골라도 규칙 정답과 같은 집합
    avail = {"delta_app": 0.5, "delta_weighted_app": 0.9, "delta_opener": 0.9, "delta_plonk": 0.1}
    ranked = rank(avail, load_ranges())
    assert apply_rules(["delta_weighted_app", "delta_app", "delta_opener"], ranked)[1] == []
    assert set(expected_picks(ranked)) == {"delta_weighted_app", "delta_app", "delta_opener"}


def test_범위_파일을_바꾸면_분류가_바뀜(tmp_path, monkeypatch):
    raw = json.loads(Path("config/light_ranges.json").read_text(encoding="utf-8"))
    raw["ranges"]["strength_split"] = [-0.3, 0.0]
    f = tmp_path / "ranges.json"
    f.write_text(json.dumps(raw), encoding="utf-8")
    monkeypatch.setenv("LLM_LIGHT_RANGES", str(f))
    assert classify("strength_split", 0.08, load_ranges()).label == "높은 편, 조금 벗어남"


def test_범위가_뒤집히면_오류(tmp_path):
    raw = json.loads(Path("config/light_ranges.json").read_text(encoding="utf-8"))
    raw["ranges"]["delta_app"] = [0.3, -0.3]
    f = tmp_path / "bad.json"
    f.write_text(json.dumps(raw), encoding="utf-8")
    with pytest.raises(ValueError):
        load_ranges(f)
