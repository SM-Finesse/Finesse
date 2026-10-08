"""엔드포인트(app/main.py) 테스트. 서버를 띄우지 않고 TestClient로 호출한다.

실행: llm-server 폴더에서  python -m pytest -v
"""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.generator import ContextOverflowError
from app.main import app
from app.schemas import LightRequest
from app.validation import check_light_output, parse_light_output

SAMPLE = Path(__file__).parent / "samples" / "light_testuser.json"
client = TestClient(app)


@pytest.fixture
def base():
    return json.loads(SAMPLE.read_text(encoding="utf-8"))


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_정상_요청이면_규칙에_맞는_응답(base):
    r = client.post("/v1/comment/light", json=base)
    assert r.status_code == 200
    resp = parse_light_output(r.text)
    available = LightRequest.model_validate(base).available_stats()
    assert check_light_output(resp, available) == []


def test_null_후보는_고르지_않음(base):
    for key in base["delta_metrics"]["playstyle_relative"]:
        base["delta_metrics"]["playstyle_relative"][key] = None
    r = client.post("/v1/comment/light", json=base)
    stats = [h["stat"] for h in r.json()["highlights"]]
    assert not set(stats) & {"delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds"}


def test_잘못된_요청은_422(base):
    base["delta_metrics"]["tr_trend_delta"] = -62.87
    r = client.post("/v1/comment/light", json=base)
    assert r.status_code == 422


def test_긴_tr_trend도_정상_응답(base):
    base["fixed_metrics"]["tr_trend"] = [20000 + i for i in range(500)]
    r = client.post("/v1/comment/light", json=base)
    assert r.status_code == 200


def test_입력_길이_초과는_413(monkeypatch, base):
    class OverflowGenerator:
        name = "overflow"

        def generate_light(self, req, messages):
            raise ContextOverflowError("Requested tokens (2668) exceed context window of 2048")

    monkeypatch.setattr(main_module, "generator", OverflowGenerator())
    r = client.post("/v1/comment/light", json=base)
    assert r.status_code == 413
    assert r.json() == {"detail": "LLM 입력 길이 초과"}


class _TextGenerator:
    """정해진 문자열을 내는 가짜 생성기."""
    name = "fixed"

    def __init__(self, text):
        self.text = text

    def generate_light(self, req, messages):
        from app.generator import GenerationResult
        return GenerationResult(text=self.text, finish_reason="stop")


def _light_text(summary, stats):
    return json.dumps({"light_summary": summary,
                       "highlights": [{"stat": s, "sentence": f"{s} 문장"} for s in stats]}, ensure_ascii=False)


def test_프롬프트를_베끼면_502(monkeypatch, base):
    text = _light_text("당신은 테트리스 게임 TETR.IO의 전적 데이터를 분석해", ["delta_plonk", "delta_app", "delta_vs_apm"])
    monkeypatch.setattr(main_module, "generator", _TextGenerator(text))
    assert client.post("/v1/comment/light", json=base).status_code == 502


def test_점_경로_키는_정리해서_200(monkeypatch, base):
    text = _light_text("요약", ["playstyle_relative.delta_plonk", "delta_app", "delta_vs_apm"]) + "\n덧붙인 글"
    monkeypatch.setattr(main_module, "generator", _TextGenerator(text))
    r = client.post("/v1/comment/light", json=base)
    assert r.status_code == 200
    assert [h["stat"] for h in r.json()["highlights"]] == ["delta_plonk", "delta_app", "delta_vs_apm"]
