"""light-v4 A/B/C (app/light_v3.py 프롬프트, app/light_pipeline.py 흐름) 테스트."""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.generator import GenerationResult, MockGenerator
from app.light_pipeline import LightFailure, LightSettings, run_light
from app.light_v3 import (
    LIGHT_A_SYSTEM,
    LIGHT_SENTENCES,
    build_light_a,
    build_light_comment,
    build_light_select,
    build_light_write,
    check_sentence,
    process_lines,
    template_sentence,
)
from app.main import app
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLES = Path(__file__).parent / "samples"
client = TestClient(app)


def _req(name="light_spread.json"):
    return LightRequest.model_validate(json.loads((SAMPLES / name).read_text(encoding="utf-8")))


class Scripted:
    """label 별로 정해 둔 출력을 차례로 낸다."""
    name = "scripted"

    def __init__(self, **outputs):
        self.outputs = {k: list(v) for k, v in outputs.items()}
        self.labels: list[str] = []

    def generate_text(self, label, messages, decoding):
        self.labels.append(label)
        key = label.split(":")[1]
        return GenerationResult(text=self.outputs[key].pop(0), finish_reason="stop")


class Clock:
    """호출마다 정해진 초만큼 흐르는 가짜 시계 (generate_text 전후로 한 번씩 읽힘)."""

    def __init__(self, gen, per_call: dict[str, float]):
        self.t, self.gen, self.per_call = 0.0, gen, per_call
        inner = gen.generate_text

        def timed(label, messages, decoding):
            self.t += self.per_call[label.split(":")[1]]
            return inner(label, messages, decoding)
        gen.generate_text = timed

    def __call__(self):
        return self.t


def _st(mode, **kw):
    return LightSettings(mode=mode, **{"attempts": 2, "budget_sec": 999, "fallback": False, **kw})


# ---------- 프롬프트 ----------
def test_A_프롬프트는_숫자_없이_두드러진_순서와_라벨():
    ctx = build_light_a(_req())
    user = ctx.messages[1]["content"]
    assert user.splitlines()[1] == ("[1] Weighted APP 차이(Cheese Index로 보정한 공격 효율) [공격] "
                                    "방향: 보정 후에도 상대보다 공격 효율이 높음 / 전체 기준: 높은 편, 많이 벗어남")
    assert "[2] Opener 성향 차이" in user and "[수비]" in user
    for line in user.splitlines()[1:]:   # 번호 [n] 뒤에는 값·범위 숫자가 없음
        assert not any(ch.isdigit() for ch in line.split("]", 1)[1])
    for key in CANDIDATE_KEYS:
        assert key not in user
    assert "어시스턴트" not in LIGHT_A_SYSTEM and "당신은" not in LIGHT_A_SYSTEM
    assert ctx.numbers == set()


def test_C_문장_프롬프트는_지표_하나만():
    ctx = build_light_select(_req())
    user = build_light_comment(ctx, "session_vs_slope")[1]["content"]
    assert "라운드별 VS 기울기" in user and "라운드가 진행될수록 처짐" in user
    assert "Opener" not in user and "Weighted" not in user


@pytest.mark.parametrize("key", list(LIGHT_SENTENCES))
@pytest.mark.parametrize("v", [0.7, -0.7, 0.0])
def test_기본_문장은_검사를_통과(key, v):
    ctx = build_light_a(LightRequest.model_validate({"fixed_metrics": {"win_rate": 0.5, "tr_trend": [1]},
                                                     "delta_metrics": {}}))
    ctx.available[key] = v
    assert check_sentence(template_sentence(key, v), ctx, key) is None


# ---------- A ----------
def test_A_규칙_위반_줄은_빼고_문장_오류와_따로_기록():
    ctx = build_light_a(_req())   # 1 weighted 2 opener 3 session 4 app 5 cheese 6 inf_ds 7 vs_apm 8..
    text = ("1 | 보정 후에도 상대보다 공격 효율이 높습니다.\n"
            "4 | 상대보다 블록당 공격량이 많습니다.\n"          # 같은 그룹(공격) → 선정 위반
            "2 | 상대보다 초반 공격 성향이 약합니다.\n"          # 방향 반대 → 문장 오류
            "3 | 라운드가 진행될수록 처집니다.\n"
            "총평 | 공격 효율이 상대보다 높습니다. 라운드 후반에는 처지는 편입니다.")
    out = process_lines(text, ctx, rule_check=True)
    assert out.raw_picks == ["delta_weighted_app", "delta_app", "delta_opener", "session_vs_slope"]
    assert out.picks == ["delta_weighted_app", "delta_opener", "session_vs_slope"]
    assert [k for k, _ in out.highlights] == ["delta_weighted_app", "session_vs_slope"]
    assert "같은 그룹" in out.rule_dropped[0] and "방향 반대" in out.sentence_dropped[0]


def test_A_줄_앞은_순서번호_문장_안에_후보번호():
    # 10/13 11번 실측 출력 모양
    ctx = build_light_a(_req("light_testuser.json"))   # [1] weighted [2] plonk [3] comeback ... [8] vs_apm [9] cheese
    text = ("번호 | 문장\n"
            "1 | 후보 8의 VS/APM 차이는 상대보다 수비 비중이 작습니다.\n"
            "2 | [9] Cheese Index 차이는 상대보다 가비지 처리 성향이 약합니다.\n"
            "3 | 후보 1의 Weighted APP 차이는 보정 후 상대보다 공격 효율이 낮습니다.\n")
    out = process_lines(text, ctx, rule_check=True, ref_in_text=True)
    assert out.raw_picks == ["delta_vs_apm", "delta_cheese_index", "delta_weighted_app"]
    assert [k for k, _ in out.highlights] == ["delta_vs_apm", "delta_cheese_index", "delta_weighted_app"]
    assert out.highlights[0][1].startswith("VS/APM 차이는")


@pytest.mark.parametrize("sentence", ["공격이 약한 플레이어를 나타낸다.", "가비지 처리가 빠른 플레이어입니다.",
                                      "고른 후보는 VS/APM 차이입니다."])
def test_유형_규정과_메타_발언은_뺌(sentence):
    ctx = build_light_a(_req("light_testuser.json"))
    assert check_sentence(sentence, ctx, None) is not None


def test_강한_상대_언급은_유형_규정이_아님():
    ctx = build_light_a(_req("light_testuser.json"))
    assert check_sentence("강한 상대를 만나면 승률이 떨어집니다.", ctx, None) is None


def test_선정_틀_베낌은_번호_없음():
    gen = Scripted(select=["번호, 번호, 번호", "1, 2, 3"],
                   write=["[1] | 보정 후에도 상대보다 공격 효율이 높습니다.\n총평 | 공격 효율이 높습니다. 차이가 납니다."] * 2)
    out = run_light(_req(), gen, _st("b"))
    assert out.attempts[0]["raw"] == [] and out.attempts[1]["picks"]


def test_A_숫자를_쓰면_뺌():
    ctx = build_light_a(_req())
    out = process_lines("1 | 공격 효율이 0.8 높습니다.\n2 | 상대보다 초반 공격 성향이 강합니다.", ctx, rule_check=True)
    assert [k for k, _ in out.highlights] == ["delta_opener"] and "숫자" in out.sentence_dropped[0]


def test_A_모자라면_재시도해서_더_나은_쪽():
    gen = Scripted(a=["1 | 보정 후에도 상대보다 공격 효율이 높습니다.",
                      "1 | 보정 후에도 상대보다 공격 효율이 높습니다.\n2 | 상대보다 초반 공격 성향이 강합니다.\n"
                      "3 | 라운드가 진행될수록 처집니다.\n총평 | 공격 효율은 높고 후반에는 처집니다. 초반 공격 성향도 강합니다."])
    out = run_light(_req(), gen, _st("a"))
    assert len(gen.labels) == 2 and len(out.highlights) == 3 and not out.summary_fallback
    assert out.picks == out.expected


def test_A_시간_예산이_모자라면_재시도하지_않음():
    gen = Scripted(a=["1 | 보정 후에도 상대보다 공격 효율이 높습니다.", "쓰이면 안 됨"])
    clock = Clock(gen, {"a": 9.0})
    out = run_light(_req(), gen, _st("a", budget_sec=14), clock=clock)
    assert len(gen.labels) == 1 and len(out.highlights) == 1 and out.summary_fallback
    assert out.budget_stops


def test_A_쓸_줄이_없으면_실패():
    gen = Scripted(a=["형식 없음", "여전히 없음"])
    with pytest.raises(LightFailure):
        run_light(_req(), gen, _st("a"))


# ---------- B ----------
def test_B_선정_위반을_다시_고르고_문장은_고른_순서로():
    gen = Scripted(select=["1, 4, 2", "1, 2, 3"],
                   write=["3 | 라운드가 진행될수록 처집니다.\n1 | 보정 후에도 상대보다 공격 효율이 높습니다.\n"
                          "2 | 상대보다 초반 공격 성향이 강합니다.\n5 | 고르지 않은 줄입니다.\n"
                          "총평 | 공격 효율이 높고 초반 공격 성향이 강합니다. 후반에는 처집니다."])
    out = run_light(_req(), gen, _st("b"))
    assert gen.labels == ["light:select", "light:select", "light:write"]
    assert out.attempts[0]["picks"] == ["delta_weighted_app", "delta_opener"]
    assert [k for k, _ in out.highlights] == ["delta_weighted_app", "delta_opener", "session_vs_slope"]
    assert not out.summary_fallback


def test_B_둘째_호출은_고른_후보만_보여줌():
    ctx = build_light_select(_req())
    user = build_light_write(ctx, ["session_vs_slope", "delta_weighted_app"])[1]["content"]
    assert user.splitlines()[1].startswith("[3] 라운드별 VS 기울기")
    assert "Opener" not in user


# ---------- C ----------
def test_C_지표별_문장_실패만_다시_쓰고_대체_문장():
    gen = Scripted(select=["1, 2, 3"],
                   comment=["보정 후에도 상대보다 공격 효율이 높습니다.",
                            "Opener tendency is strong.", "Opener 성향이 상대보다 0.9 강합니다.",   # 두 번 다 실패
                            "라운드가 진행될수록 처집니다."],
                   summary=["공격 효율은 높지만 라운드 후반에는 처집니다. 경기 운영을 함께 보면 좋습니다."])
    out = run_light(_req(), gen, _st("c", fallback=True))
    assert gen.labels.count("light:comment") == 4
    assert out.sentence_fallback == ["delta_opener"]
    assert dict(out.highlights)["delta_opener"] == template_sentence("delta_opener", 0.9)
    assert not out.summary_fallback


def test_C_시간_예산이면_총평을_서버가():
    gen = Scripted(select=["1, 2, 3"], comment=["보정 후에도 상대보다 공격 효율이 높습니다.",
                                                "상대보다 초반 공격 성향이 강합니다.", "라운드가 진행될수록 처집니다."],
                   summary=["쓰이면 안 됨."])
    clock = Clock(gen, {"select": 3.0, "comment": 3.0, "summary": 3.5})
    out = run_light(_req(), gen, _st("c", budget_sec=14), clock=clock)   # 3+9=12, 총평 3.5 → 15.5 > 14
    assert "light:summary" not in gen.labels and out.summary_fallback and len(out.highlights) == 3


# ---------- 엔드포인트 (Mock) ----------
@pytest.mark.parametrize("mode", ["a", "b", "c", "c2", "d"])
@pytest.mark.parametrize("sample", ["light_testuser.json", "light_pos.json", "light_spread.json", "light_group.json"])
def test_엔드포인트_Mock_모드별_200(monkeypatch, mode, sample):
    monkeypatch.setenv("LLM_LIGHT_MODE", mode)
    monkeypatch.setattr(main_module, "generator", MockGenerator())
    r = client.post("/v1/comment/light", json=json.loads((SAMPLES / sample).read_text(encoding="utf-8")))
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"light_summary", "highlights"}
    assert len(body["highlights"]) == 3 and all(h["stat"] in CANDIDATE_KEYS for h in body["highlights"])


def test_엔드포인트_실패는_502(monkeypatch):
    monkeypatch.setenv("LLM_LIGHT_MODE", "b")
    monkeypatch.setattr(main_module, "generator", Scripted(select=["모름", "모름"]))
    r = client.post("/v1/comment/light", json=json.loads((SAMPLES / "light_spread.json").read_text(encoding="utf-8")))
    assert r.status_code == 502


def test_잘못된_모드는_서버_오류로_알림(monkeypatch):
    monkeypatch.setenv("LLM_LIGHT_MODE", "x")
    with pytest.raises(ValueError):
        LightSettings.from_env()


# ---------- 10/13 11번 확인2 실측 출력 ----------
def test_A_이름만_쓴_줄은_문장이_아님():
    ctx = build_light_a(_req("light_testuser.json"))
    text = ("1. **Weighted APP 차이(Cheese Index로 보정한 공격 효율)**\n"
            "   - 방향: 보정 후 상대보다 공격 효율이 낮음 / 전체 기준: 낮은 편, 많이 벗어남\n"
            "2. **Plonk 성향 차이(효율·가비지 활용 위주 성향)**\n")
    out = process_lines(text, ctx, rule_check=True, ref_in_text=True, allow_empty=True)
    assert out.highlights == [] and all("문장이 아님" in d for d in out.sentence_dropped)


def test_B_대괄호_번호_뒤_구분자_없는_줄():
    ctx = build_light_select(_req("light_testuser.json"))
    picks = ["delta_weighted_app", "delta_plonk", "delta_comeback"]
    text = ("총평\n"
            "[1] Weighted APP 차이(Cheese Index로 보정한 공격 효율)는 상대보다 공격 효율이 낮습니다.\n"
            "[2] Plonk 성향 차이는 전체 기준에서는 보통입니다.\n")
    out = process_lines(text, ctx, rule_check=False, allowed=picks, ref_in_text=True, allow_empty=True)
    assert [k for k, _ in out.highlights] == ["delta_weighted_app"]
    assert "프롬프트 베낌" in out.sentence_dropped[0]   # '전체 기준' 노출


def test_C_총평_머리말과_목록():
    from app.light_v3 import process_summary
    ctx = build_light_select(_req("light_testuser.json"))
    text = ("하이라이트를 요약한 두 문장:\n\n1. 가비지 처리 성향을 보정한 공격 효율은 상대보다 낮습니다.\n"
            "2. 역전 경향은 뒤집히는 경우가 더 많습니다.")
    summary, why = process_summary(text, ctx)
    assert why is None and summary.startswith("가비지 처리 성향을 보정한") and summary.endswith("많습니다.")


# ---------- C v2 · D(3회) · 그룹 함정 (10/14) ----------
def test_그룹_함정_샘플은_앞_3개가_정답이_아님():
    from app.light_rules import apply_rules, expected_picks
    ctx = build_light_select(_req("light_group.json"))
    assert ctx.order[:4] == ["delta_weighted_app", "delta_app", "delta_opener", "delta_cheese_index"]
    assert expected_picks(ctx.ranked) == ["delta_weighted_app", "delta_opener", "delta_cheese_index"]
    accepted, dropped = apply_rules(ctx.order[:3], ctx.ranked)          # 습관대로 [1] [2] [3]
    assert accepted == ["delta_weighted_app", "delta_opener"] and "같은 그룹" in dropped[0]


@pytest.mark.parametrize("v, sentence, ok", [
    (-0.18, "역전 경향 차이는 유리한 경기를 뒤집히는 경우가 불리한 경기를 뒤집는 경우보다 많습니다.", True),
    (-0.18, "역전 경향 차이는 뒤집는 경향이 유리한 경우가 불리한 경우보다 더 많습니다.", False),   # 한쪽만 말함
    (-0.18, "역전 경향 차이는 뒤집는 경향이 불리할 때 강하게 반응합니다.", False),              # '보다' 없음
    (-0.18, "불리한 경기를 뒤집는 경우가 유리한 경기를 뒤집히는 경우보다 많습니다.", False),        # 반대
    (0.25, "역전 경향 차이(역전승률 − 역전 허용률): 불리한 경기를 뒤집는 경우가 유리한 경기를 뒤집히는 경우보다 많습니다.", True),
    (0.25, "유리한 경기를 뒤집히는 경우가 불리한 경기를 뒤집는 경우보다 적습니다.", True),          # '적다' 로 뒤집음
])
def test_역전_방향_검사(v, sentence, ok):
    ctx = build_light_select(_req("light_testuser.json"))
    ctx.available["delta_comeback"] = v
    assert (check_sentence(sentence, ctx, "delta_comeback") is None) == ok


@pytest.mark.parametrize("sentence, why", [
    ("그 플레이어는 상대보다 효율과 가비지 활용 위주의 성향을 보여줍니다.", "방향 없음"),
    ("상대보다 효율과 가비지 활용 위주 성향(Plonk)이 보통입니다.", "내부 라벨"),
    ("플로킹 성향 차이는 상대보다 약합니다.", "다른 분야"),
])
def test_C_v1_오류_문장(sentence, why):
    ctx = build_light_select(_req("light_testuser.json"))     # plonk -0.22
    assert why in (check_sentence(sentence, ctx, "delta_plonk") or "")


def test_하이라이트_문장_머리말():
    from app.light_v3 import process_comment
    ctx = build_light_select(_req("light_testuser.json"))
    s, why = process_comment("하이라이트 문장: Plonk 성향 차이는 상대보다 약합니다.", ctx, "delta_plonk")
    assert why is None and s == "Plonk 성향 차이는 상대보다 약합니다."


def test_C_v2_문장_프롬프트는_해석과_전체기준_없음():
    ctx = build_light_select(_req())
    v1 = build_light_comment(ctx, "session_vs_slope")[1]["content"]
    v2 = build_light_comment(ctx, "session_vs_slope", extras=False)[1]["content"]
    assert "해석:" in v1 and "전체 기준:" in v1
    assert "해석:" not in v2 and "전체 기준:" not in v2 and "방향:" in v2 and "예시:" in v2


def test_D_3회_선정_문장3_총평():
    gen = Scripted(select=["[1] [2] [3]"],
                   write=["[1] | 보정 후에도 상대보다 공격 효율이 높습니다.\n[2] | 상대보다 초반 공격 성향이 강합니다.\n"
                          "[3] | 라운드가 진행될수록 공방 능력이 떨어집니다."],
                   summary=["공격 효율과 초반 공격 성향은 상대보다 강합니다. 다만 라운드 후반에는 처집니다."])
    out = run_light(_req(), gen, _st("d"))
    assert gen.labels == ["light:select", "light:write", "light:summary"]
    assert len(out.highlights) == 3 and not out.summary_fallback


def test_성향을_우열로_쓰면_뺌():
    ctx = build_light_select(_req("light_pos.json"))        # plonk +0.18
    assert "우열" in check_sentence("그 플레이어는 상대보다 효율과 가비지 활용을 중점을 두고 잘하는 성향입니다.", ctx, "delta_plonk")
    assert check_sentence("상대보다 효율과 가비지 활용을 중시합니다.", ctx, "delta_plonk") is None
