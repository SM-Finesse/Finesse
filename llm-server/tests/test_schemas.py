"""요청 스키마(app/schemas.py) 테스트.

실행: llm-server 폴더에서  python -m pytest -v
"""
import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas import LightRequest

SAMPLE = Path(__file__).parent / "samples" / "light_testuser.json"


@pytest.fixture
def base():
    """Payload Mock testuser light 출력의 안쪽 (llmRequest 껍데기 제거)."""
    return json.loads(SAMPLE.read_text(encoding="utf-8"))


def test_정상_샘플은_후보_11개(base):
    req = LightRequest.model_validate(base)
    assert len(req.available_stats()) == 11


def test_delta_comeback_null이면_후보에서_빠짐(base):
    base["delta_metrics"]["delta_comeback"] = None
    stats = LightRequest.model_validate(base).available_stats()
    assert "delta_comeback" not in stats
    assert len(stats) == 10


def test_delta_comeback_생략도_null과_똑같이_처리(base):
    del base["delta_metrics"]["delta_comeback"]
    stats = LightRequest.model_validate(base).available_stats()
    assert "delta_comeback" not in stats
    assert len(stats) == 10


def test_플레이스타일_4개_null이면_후보_7개(base):
    for key in base["delta_metrics"]["playstyle_relative"]:
        base["delta_metrics"]["playstyle_relative"][key] = None
    assert len(LightRequest.model_validate(base).available_stats()) == 7


def test_tr_trend_delta가_섞이면_거절(base):
    base["delta_metrics"]["tr_trend_delta"] = -62.87
    with pytest.raises(ValidationError):
        LightRequest.model_validate(base)


def test_llmRequest_껍데기째_넣으면_거절(base):
    with pytest.raises(ValidationError):
        LightRequest.model_validate({"llmRequest": copy.deepcopy(base)})


def test_win_rate_범위_밖이면_거절(base):
    base["fixed_metrics"]["win_rate"] = 1.5
    with pytest.raises(ValidationError):
        LightRequest.model_validate(base)


def test_숫자_자리에_문자열이면_거절(base):
    base["delta_metrics"]["strength_split"] = "high"
    with pytest.raises(ValidationError):
        LightRequest.model_validate(base)