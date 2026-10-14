"""light-6call (34번 문서) — app/light6.py · app/light6_flow.py · /v1/light/* 엔드포인트 테스트."""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.generator import GenerationResult, MockGenerator
from app.light6 import (
    build_judge,
    build_select,
    check_comment,
    example_sentence,
    expected_selection,
    judge_one,
    load_quartiles,
    parse_judge,
    parse_select,
    round1,
)
from app.light6_flow import Flow6Settings, run_light6
from app.main import app
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLES = Path(__file__).parent / "samples"
client = TestClient(app)
Q = load_quartiles()


def _values(name):
    r = LightRequest.model_validate(json.loads((SAMPLES / f"{name}.json").read_text(encoding="utf-8")))
    return {k: None for k in CANDIDATE_KEYS} | r.available_stats()


def _cands(name):
    v = _values(name)
    return [(k, v[k], *Q[k]) for k in CANDIDATE_KEYS]


# ---------- 0. 계산 (34번 3.0절) ----------
def test_보통_범위는_31번_2_2절_값():
    assert Q["delta_opener"] == (-0.249, 0.171) and Q["session_vs_slope"] == (-0.080, 1.370)
    assert len(Q) == 11


@pytest.mark.parametrize("v, q1, q3, want", [
    (0.30, -0.10, 0.10, ("높음", 1.0)),
    (-0.28, -0.10, 0.10, ("낮음", -0.9)),
    (0.05, -0.10, 0.10, ("보통", 0.0)),
    (0.10, -0.10, 0.10, ("보통", 0.0)),          # 경계값은 범위 안
    (0.027, -0.026, 0.026, ("높음", 0.0)),       # 범위 밖이지만 반올림하면 0 → 영역은 높음 그대로
    (None, -0.1, 0.1, ("없음", None)),
])
def test_판정_계산(v, q1, q3, want):
    assert judge_one(v, q1, q3) == want


def test_반올림은_사사오입():
    assert round1(0.25) == 0.3 and round1(0.35) == 0.4 and round1(-0.25) == -0.3


@pytest.mark.parametrize("name, want", [
    ("l6_typical", ["delta_comeback", "delta_stride", "delta_weighted_app"]),     # Stride·Weighted 동점 → 고정 순서
    ("l6_group", ["delta_app", "delta_opener", "delta_cheese_index"]),            # Weighted 는 같은 공격 그룹
    ("l6_nullstyle", ["strength_split", "delta_app", "delta_vs_apm"]),
    ("l6_nearzero", ["delta_weighted_app", "delta_opener", "delta_vs_apm"]),      # 보통 단계도 그룹 겹침 피함
    ("l6_negative", ["delta_comeback", "strength_split", "delta_vs_apm"]),
    ("l6_mixed", ["delta_cheese_index", "session_vs_slope", "delta_inf_ds"]),
])
def test_선정_규칙_정답(name, want):
    v = _values(name)
    assert expected_selection({k: judge_one(v[k], *Q[k]) for k in CANDIDATE_KEYS}) == want


# ---------- 1단계 판정 ----------
def test_판정_프롬프트는_값과_범위_폭을_보여줌():
    user = build_judge(_cands("l6_nullstyle"))[1]["content"]
    assert "[1] Opener 성향: 값 없음, Q1 -0.249, Q3 +0.171, 폭 0.420" in user
    assert "[10] 역전 경향: 값 -0.02," in user
    for k in CANDIDATE_KEYS:
        assert k not in user


def _answer(cands, override=None):
    lines = []
    for i, (k, v, q1, q3) in enumerate(cands, 1):
        z, d = (override or {}).get(k) or judge_one(v, q1, q3)
        lines.append(f"[{i}] | {z} | {'-' if d is None else f'{d:+.1f}'}")
    return "\n".join(lines)


def test_판정_정답이면_통과_허용오차_0_1():
    c = _cands("l6_typical")
    assert parse_judge(_answer(c), c).errors == []
    assert parse_judge(_answer(c, {"delta_comeback": ("높음", 0.8)}), c).errors == []      # 0.7 ± 0.1
    r = parse_judge(_answer(c, {"delta_comeback": ("높음", 0.9)}), c)
    assert len(r.errors) == 1 and not r.item_ok["delta_comeback"]


def test_판정_줄_모양이_달라도_읽음():
    c = _cands("l6_typical")[:3]
    text = "1. Opener 성향 | 보통 | 0.0\n[2] 보통, 0.0\n[3] Stride 성향: 높음 (+0.3)"
    assert parse_judge(text, c).errors == []


def test_판정_줄이_빠지거나_영역이_틀리면_오류():
    c = _cands("l6_typical")
    text = "\n".join(_answer(c).splitlines()[:-1])
    assert any("줄 없음" in e for e in parse_judge(text, c).errors)
    assert parse_judge(_answer(c, {"delta_opener": ("높음", 0.0)}), c).errors


# ---------- 2단계 선정 ----------
def _jl(name):
    v = _values(name)
    return [(k, *judge_one(v[k], *Q[k])) for k in CANDIDATE_KEYS]


def test_선정_프롬프트는_고정_순서_그대로():
    user = build_select(_jl("l6_group"))[1]["content"]
    assert user.splitlines()[1].startswith("[1] Opener 성향 (그룹: 플레이스타일): 영역 높음, 벗어난 정도 +0.")
    assert "[5] 블록당 공격량(APP) (그룹: 공격): 영역 높음, 벗어난 정도 +0.9" in user


def test_선정_번호_읽기와_검증2():
    jl = _jl("l6_nullstyle")                       # 1~4 는 없음
    r = parse_select("[9], [1], [5], [9], [7]", jl)
    assert r.stats == ["strength_split", "delta_app", "delta_vs_apm"]
    assert any("없음" in d for d in r.dropped) and any("반복" in d for d in r.dropped)
    r = parse_select("9, 5, 7\n[9] 상대 강도별 승률 차: 벗어난 정도 +0.7", jl)   # 소수 속 숫자는 번호로 읽지 않음
    assert r.raw[:3] == ["strength_split", "delta_app", "delta_vs_apm"]


# ---------- 3단계 검증 ③ (31번 1.2·1.3·1.4) ----------
@pytest.mark.parametrize("k, v, text, ok", [
    ("delta_app", 0.02, "상대 대비 블록당 공격량에서 앞섭니다(+0.02).", True),                 # 31번 1.3 예시 ○
    ("delta_opener", 0.10, "맞붙은 상대들에 비해 Opener 성향이 짙게 나타납니다(+0.10).", True),   # ○
    ("delta_app", 0.02, "공격 효율에서 앞섭니다.", False),                                    # 비교 기준 없음 ✗
    ("delta_opener", 0.10, "Opener 성향이 더 짙습니다.", False),                              # ✗
    ("delta_app", 0.02, "상대 대비 블록당 공격량이 2% 높습니다.", False),                       # 백분율 ✗
    ("delta_vs_apm", 0.1, "상대 대비 수비 비중이 커서 수비형 플레이어입니다.", False),            # 유형 규정 ✗
    ("delta_plonk", 0.1, "상대 대비 Plonk 성향에서 앞섭니다.", False),                         # 성향에 우열 ✗
    ("delta_plonk", -0.2, "맞붙은 상대들에 비해 Plonk 성향이 짙게 나타납니다.", False),           # 방향 반대 ✗
    ("delta_app", 0.02, "다른 유저들보다 블록당 공격량이 많습니다.", False),                    # ✗
    ("delta_app", 0.02, "상대 대비 최근 5경기 연승 중 블록당 공격량에서 앞섭니다.", False),       # 최근 흐름 ✗
    ("session_vs_slope", 0.9, "상대보다 라운드가 갈수록 강해집니다.", False),                   # session 에 상대 ✗
    ("session_vs_slope", 0.0, "라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다.", True),
    ("strength_split", -0.6, "강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 크게 낮습니다.", True),
    ("strength_split", -0.6, "상대 대비 강한 상대 구간 승률이 낮습니다.", False),
    ("delta_comeback", -0.15, "유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강합니다.", True),
    ("delta_comeback", -0.15, "불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다.", False),
    ("delta_app", 0.02, "상대 대비 블록당 공격량이 보통 범위를 벗어납니다.", False),            # 판정 정보 노출 ✗
    ("delta_app", 0.02, "상대 대비 블록당 공격량에서 앞섭니다(+0.05).", False),                # 입력에 없는 숫자 ✗
])
def test_검증3(k, v, text, ok):
    assert (check_comment(text, [(k, v)], single=True) is None) == ok


@pytest.mark.parametrize("k", CANDIDATE_KEYS)
@pytest.mark.parametrize("v", [0.3, -0.3, 0.0])
def test_예문은_검증을_통과(k, v):
    assert check_comment(example_sentence(k, v), [(k, v)], single=True) is None


# ---------- 흐름 (백엔드 재현) ----------
class Scripted:
    name = "scripted"

    def __init__(self, **outs):
        self.outs = {k: list(v) for k, v in outs.items()}
        self.labels = []

    def generate_text(self, label, messages, decoding):
        self.labels.append(label)
        stage = label.split(":")[1]
        return GenerationResult(text=self.outs[stage].pop(0), finish_reason="stop")


def test_흐름_Mock_6회_정답():
    out = run_light6(_values("l6_group"), MockGenerator())
    assert out.status == 200 and out.picks == out.expected == ["delta_app", "delta_opener", "delta_cheese_index"]
    assert len(out.calls) == 6 and all(s for _, s in out.highlights) and out.summary


def test_흐름_판정은_그_단계만_재요청():
    c = _cands("l6_typical")
    gen = Scripted(judge=[_answer(c, {"delta_opener": ("높음", 0.5)}), _answer(c)],
                   select=["[10], [3], [6]"],
                   sentence=["불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다.",
                             "맞붙은 상대들에 비해 Stride 성향이 짙게 나타납니다.",
                             "상대 대비 보정 공격 효율에서 앞섭니다."],
                   summary=["맞붙은 상대들에 비해 Stride 성향이 짙고, 상대 대비 보정 공격 효율에서 앞섭니다. 뒤집는 경향도 강합니다."])
    out = run_light6(_values("l6_typical"), gen, Flow6Settings(attempts=2))
    assert gen.labels.count("light6:judge") == 2 and out.judge_attempts == 2
    assert out.judge_first_items["delta_opener"] is False and out.status == 200
    assert out.picks == ["delta_comeback", "delta_stride", "delta_weighted_app"]


def test_흐름_문장_실패는_그_호출만_끝내_실패면_수치만():
    c = _cands("l6_typical")
    gen = Scripted(judge=[_answer(c)], select=["[10], [3], [6]"],
                   sentence=["불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다.",
                             "Stride 성향이 짙습니다.", "Stride 성향이 더 짙습니다.",       # 비교 기준 누락 ×2
                             "상대 대비 보정 공격 효율에서 앞섭니다."],
                   summary=["요약", "요약"])                                                # 문장 아님 ×2
    out = run_light6(_values("l6_typical"), gen, Flow6Settings(attempts=2))
    assert dict(out.highlights)["delta_stride"] is None and out.summary is None
    assert gen.labels.count("light6:judge") == 1 and gen.labels.count("light6:select") == 1


def test_흐름_시간_환산():
    out = run_light6(_values("l6_typical"), MockGenerator())
    out.stage_sec = {"judge": 4.0, "select": 2.0, "sentence:a": 2.5, "sentence:b": 2.0, "sentence:c": 2.0,
                     "summary": 3.0}
    assert out.est(1) == pytest.approx(15.5) and out.est(2) == pytest.approx(11.0)   # 3.0+2.0 | 2.5+2.0
    assert out.est(4) == pytest.approx(9.0)


# ---------- 엔드포인트 ----------
@pytest.fixture
def mock(monkeypatch):
    monkeypatch.setattr(main_module, "generator", MockGenerator())


def _judge_body(name):
    v = _values(name)
    body = []
    for k in CANDIDATE_KEYS:
        item = {"stat": k, "q1": Q[k][0], "q3": Q[k][1]}
        if v[k] is not None:
            item["value"] = v[k]                      # 백엔드는 null 을 생략한다
        body.append(item)
    return {"candidates": body}


def test_엔드포인트_6회_연동(mock):
    r = client.post("/v1/light/judge", json=_judge_body("l6_nullstyle"))
    assert r.status_code == 200
    judgments = r.json()["judgments"]
    assert [j["stat"] for j in judgments] == list(CANDIDATE_KEYS)
    assert judgments[0] == {"stat": "delta_opener", "zone": "없음", "deviation": None}
    r = client.post("/v1/light/select", json={"judgments": judgments})
    assert r.status_code == 200
    stats = r.json()["stats"][:3]
    assert stats == ["strength_split", "delta_app", "delta_vs_apm"]
    v = _values("l6_nullstyle")
    for k in stats:
        r = client.post("/v1/light/sentence", json={"stat": k, "value": v[k], "zone": "높음"})
        assert r.status_code == 200 and r.json()["stat"] == k and r.json()["sentence"]
    r = client.post("/v1/light/summary", json={"items": [{"stat": k, "value": v[k]} for k in stats]})
    assert r.status_code == 200 and r.json()["light_summary"]


def test_엔드포인트_검증_실패는_502(monkeypatch):
    monkeypatch.setattr(main_module, "generator", Scripted(judge=["[1] | 높음 | +9.9"], select=["[1]"],
                                                           sentence=["Opener tendency is strong."],
                                                           summary=["요약입니다."]))
    assert client.post("/v1/light/judge", json=_judge_body("l6_typical")).status_code == 502
    jl = [{"stat": k, "zone": "보통", "deviation": 0.0} for k in CANDIDATE_KEYS]
    assert client.post("/v1/light/select", json={"judgments": jl}).status_code == 502          # 1개 < 3
    assert client.post("/v1/light/sentence", json={"stat": "delta_opener", "value": 0.3}).status_code == 502
    assert client.post("/v1/light/summary", json={"items": [{"stat": "delta_app", "value": 0.03}]}).status_code == 502


def test_엔드포인트_요청_검증_422(mock):
    body = _judge_body("l6_typical")
    body["candidates"].append(dict(body["candidates"][0]))
    assert client.post("/v1/light/judge", json=body).status_code == 422                     # 같은 stat 두 번
    assert client.post("/v1/light/sentence", json={"stat": "tr_trend_delta", "value": 1}).status_code == 422
    jl = [{"stat": k, "zone": "없음"} for k in CANDIDATE_KEYS]
    assert client.post("/v1/light/select", json={"judgments": jl}).status_code == 422


def test_엔드포인트_입력_길이_초과는_413(monkeypatch):
    from app.generator import ContextOverflowError

    class Over:
        name = "over"

        def generate_text(self, label, messages, decoding):
            raise ContextOverflowError("exceed context window")

    monkeypatch.setattr(main_module, "generator", Over())
    assert client.post("/v1/light/judge", json=_judge_body("l6_typical")).status_code == 413


def test_기존_엔드포인트_six_모드(mock, monkeypatch):
    monkeypatch.setenv("LLM_LIGHT_MODE", "six")
    body = json.loads((SAMPLES / "l6_group.json").read_text(encoding="utf-8"))
    r = client.post("/v1/comment/light", json=body)
    assert r.status_code == 200
    assert [h["stat"] for h in r.json()["highlights"]] == ["delta_app", "delta_opener", "delta_cheese_index"]


def test_판정_표_줄도_읽음():
    c = _cands("l6_typical")[:2]
    assert parse_judge("| 대괄호 번호 | 영역 | 벗어난 정도 |\n| --- | --- | --- |\n| 1 | 보통 | 0.0 |\n| 2 | 보통 | 0.0 |", c).errors == []


def test_측정용_both_모드는_판정_실패해도_2_3단계를_계속():
    gen = Scripted(judge=["엉뚱한 출력", "또 엉뚱한 출력"], select=["[10], [3], [6]"],
                   sentence=["불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다.",
                             "맞붙은 상대들에 비해 Stride 성향이 짙게 나타납니다.",
                             "상대 대비 보정 공격 효율에서 앞섭니다."],
                   summary=["맞붙은 상대들에 비해 Stride 성향이 짙고, 상대 대비 보정 공격 효율에서 앞섭니다."])
    out = run_light6(_values("l6_typical"), gen, Flow6Settings(attempts=2, judge="both"))
    assert not out.judge_ok and out.status == 200 and out.picks == ["delta_comeback", "delta_stride", "delta_weighted_app"]
    out = run_light6(_values("l6_typical"), Scripted(judge=["x", "y"]), Flow6Settings(attempts=2, judge="llm"))
    assert out.status == 502
