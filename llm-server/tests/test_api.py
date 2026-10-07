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
