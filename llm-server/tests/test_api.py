"""엔드포인트(app/main.py) 테스트. 서버를 띄우지 않고 TestClient로 호출한다.

실행: llm-server 폴더에서  python -m pytest -v
"""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

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