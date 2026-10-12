"""프롬프트 조립(app/prompt.py) 테스트.

실행: llm-server 폴더에서  python -m pytest -v
"""
import json
import re
from pathlib import Path

import pytest

from app.prompt import LIGHT_DECODING, LIGHT_SYSTEM_PROMPT, build_light_messages
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLE = Path(__file__).parent / "samples" / "light_testuser.json"


@pytest.fixture
def base():
    return json.loads(SAMPLE.read_text(encoding="utf-8"))


def test_메시지는_system_user_순서(base):
    messages = build_light_messages(LightRequest.model_validate(base))
    assert [m["role"] for m in messages] == ["system", "user"]


def test_user_메시지는_요청과_같은_JSON(base):
    messages = build_light_messages(LightRequest.model_validate(base))
    assert json.loads(messages[1]["content"]) == base


def test_생략된_필드는_생략된_채로_전달(base):
    del base["delta_metrics"]["delta_comeback"]
    messages = build_light_messages(LightRequest.model_validate(base))
    sent = json.loads(messages[1]["content"])
    assert "delta_comeback" not in sent["delta_metrics"]


def test_null_필드는_null로_전달(base):
    base["delta_metrics"]["delta_comeback"] = None
    messages = build_light_messages(LightRequest.model_validate(base))
    sent = json.loads(messages[1]["content"])
    assert sent["delta_metrics"]["delta_comeback"] is None


def test_규칙12의_키_목록이_후보_11개와_일치():
    # 프롬프트 문구와 코드의 후보 목록이 어긋나지 않게 지킨다
    rule12 = LIGHT_SYSTEM_PROMPT.split("12.")[1]
    keys_in_prompt = re.findall(r"[a-z_]+", rule12.split(":", 1)[1])
    assert tuple(keys_in_prompt) == CANDIDATE_KEYS


def test_프롬프트에_삭제된_항목이_없음():
    # v1.2에서 삭제된 summary_hint·partial, light 후보에서 빠진 tr_trend_delta
    for word in ("summary_hint", "partial", "tr_trend_delta"):
        assert word not in LIGHT_SYSTEM_PROMPT


def test_디코딩_파라미터():
    assert LIGHT_DECODING == {"temperature": 0.4, "top_p": 0.9, "max_tokens": 377}