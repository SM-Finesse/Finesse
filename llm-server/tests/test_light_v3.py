"""light-v3 (app/light_v3.py) 테스트."""
import json
import random
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.light_v3 import LIGHT_V3_SYSTEM, build_light_v3, process_light_v3, size_label
from app.main import app
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLES = Path(__file__).parent / "samples"
client = TestClient(app)


def _req(name="light_testuser.json"):
    return LightRequest.model_validate(json.loads((SAMPLES / name).read_text(encoding="utf-8")))


def _ctx(name="light_testuser.json", **kw):
    kw.setdefault("shuffle", False)
    kw.setdefault("size_hint", False)
    return build_light_v3(_req(name), **kw)


def test_후보는_번호와_한국어_이름_방향으로():
    ctx = _ctx()
    user = ctx.messages[1]["content"]
    assert ctx.order == list(_req().available_stats())
    assert "[1] Opener 성향 차이(초반 공격 위주 성향): +0.1 (상대보다 강함)" in user
    assert "Cheese Index 차이(가비지 처리 성향): -2.56 (상대보다 가비지 처리 성향이 약함)" in user
    for key in CANDIDATE_KEYS:
        assert key not in user  # 영문 키를 보여 주지 않음


def test_시스템_프롬프트에_어시스턴트_문장_없음():
    assert "어시스턴트" not in LIGHT_V3_SYSTEM and "당신은" not in LIGHT_V3_SYSTEM


def test_크기_표시는_켰을_때만():
    assert "차이 보통" not in _ctx().messages[1]["content"]
    assert "+0.1 (상대보다 강함, 차이 보통)" in _ctx(size_hint=True).messages[1]["content"]  # 0.1/0.5 = 0.2


@pytest.mark.parametrize("key, v, label", [
    ("delta_opener", 0.05, "차이 작음"), ("delta_opener", 0.18, "차이 보통"), ("delta_opener", 0.4, "차이 큼"),
    ("delta_cheese_index", 1.9, "차이 작음"), ("session_vs_slope", -15, "차이 큼"), ("strength_split", -0.3, None),
])
def test_크기_라벨(key, v, label):
    assert size_label(key, v) == label


def test_섞기는_켰을_때만_순서가_바뀜():
    base = _ctx().order
    mixed = _ctx(shuffle=True, rng=random.Random(3)).order
    assert sorted(mixed) == sorted(base) and mixed != base


def test_정상_출력():
    ctx = _ctx()   # 1 opener +0.1, 2 plonk -0.22, ..., 8 cheese -2.56
    text = ("2 | Plonk 성향이 상대보다 약합니다.\n"
            "[8] | Cheese Index가 상대보다 낮아 가비지 처리 성향이 약합니다.\n"
            "1: Opener 성향은 상대보다 강합니다.\n"
            "총평 | 상대보다 초반 공격 쪽은 강하지만 가비지 처리 성향은 약합니다. 수비 쪽에서 차이가 납니다.")
    out = process_light_v3(text, ctx)
    assert [k for k, _ in out.highlights] == ["delta_plonk", "delta_cheese_index", "delta_opener"]
    assert not out.summary_fallback and out.dropped == []


@pytest.mark.parametrize("line, why", [
    ("2 | Plonk 성향이 상대보다 강합니다.", "방향 반대"),
    ("2 | Plonk 성향이 상대보다 22% 약합니다.", "입력에 없는 숫자"),   # 0.22 → 22 는 허용, 아래 별도 확인
    ("2 | Plonk strength is lower.", "한국어 문장 아님"),
    ("2 | delta_plonk 값이 약합니다.", "영어·변수 이름"),
    ("2 | 플레이스타일防御가 약합니다.", "중국어"),
    ("2 | 번호 | 문장 형식으로 씁니다.", "프롬프트 베낌"),
])
def test_틀린_줄은_뺌(line, why):
    ctx = _ctx()
    text = line + "\n3 | Stride 성향이 상대보다 약합니다.\n총평 | 상대보다 속도 쪽 성향이 약합니다. 차이가 보입니다."
    if why == "입력에 없는 숫자":
        text = text.replace("22%", "37%")
    out = process_light_v3(text, ctx)
    assert [k for k, _ in out.highlights] == ["delta_stride"]
    assert any(why.split()[0] in d for d in out.dropped)


def test_백분율로_바꾼_값은_허용():
    out = process_light_v3("2 | Plonk 성향이 상대보다 22% 약합니다.", _ctx())
    assert out.highlights[0][0] == "delta_plonk"


def test_같은_번호_반복과_없는_번호():
    out = process_light_v3("2 | Plonk 성향이 약합니다.\n2 | Plonk 성향이 약합니다.\n40 | 없는 번호입니다.", _ctx())
    assert len(out.highlights) == 1 and any("반복" in d for d in out.dropped) and any("없는 번호" in d for d in out.dropped)


def test_총평이_없거나_틀리면_서버가_만듦():
    ctx = _ctx()
    out = process_light_v3("2 | Plonk 성향이 상대보다 약합니다.\n총평 | The player is weak.", ctx)
    assert out.summary_fallback and "Plonk 성향" in out.summary
    out = process_light_v3("2 | Plonk 성향이 상대보다 약합니다.", ctx)
    assert out.summary_fallback


def test_쓸_수_있는_줄이_없으면_오류():
    with pytest.raises(ValueError):
        process_light_v3('{"light_summary":"당신은 테트리스"}', _ctx())


def test_v3_엔드포인트_Mock_200(monkeypatch):
    monkeypatch.setenv("LLM_LIGHT_MODE", "v3")
    r = client.post("/v1/comment/light", json=json.loads((SAMPLES / "light_testuser.json").read_text(encoding="utf-8")))
    assert r.status_code == 200
    body = r.json()
    assert len(body["highlights"]) == 3 and all(h["stat"] in CANDIDATE_KEYS for h in body["highlights"])


def test_v3_엔드포인트_쓸_줄이_없으면_502(monkeypatch):
    from app.generator import GenerationResult

    class Bad:
        name = "bad"

        def generate_light_text(self, req, messages):
            return GenerationResult(text="아무 형식도 아닌 글", finish_reason="stop")

    monkeypatch.setenv("LLM_LIGHT_MODE", "v3")
    monkeypatch.setattr(main_module, "generator", Bad())
    r = client.post("/v1/comment/light", json=json.loads((SAMPLES / "light_testuser.json").read_text(encoding="utf-8")))
    assert r.status_code == 502
