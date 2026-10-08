"""생성기(app/generator.py) 테스트. 실제 모델 없이 가짜 llama_cpp 로 시험한다.

실행: llm-server 폴더에서  python -m pytest -v
"""
import json
import sys
import types
from pathlib import Path

import pytest

from app.generator import (
    ContextOverflowError,
    LlamaGenerator,
    MockGenerator,
    build_generator,
    light_response_schema,
)
from app.prompt import build_light_messages
from app.schemas import CANDIDATE_KEYS, LightRequest

SAMPLE = Path(__file__).parent / "samples" / "light_testuser.json"


@pytest.fixture
def req():
    return LightRequest.model_validate(json.loads(SAMPLE.read_text(encoding="utf-8")))


@pytest.fixture
def fake_llama(monkeypatch):
    """create_chat_completion 에 넘어온 인자를 기록하는 가짜 llama_cpp 모듈."""
    calls = []

    class FakeLlama:
        def __init__(self, **kwargs):
            calls.append(("init", kwargs))

        def create_chat_completion(self, **kwargs):
            calls.append(("chat", kwargs))
            return {
                "choices": [{"message": {"content": '{"light_summary":"s","highlights":[]}'},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1},
            }

    monkeypatch.setitem(sys.modules, "llama_cpp", types.SimpleNamespace(Llama=FakeLlama))
    return calls


def test_환경변수가_없으면_Mock(monkeypatch):
    monkeypatch.delenv("LLM_GENERATOR", raising=False)
    assert isinstance(build_generator(), MockGenerator)


def test_llama인데_모델경로가_없으면_에러(monkeypatch):
    monkeypatch.setenv("LLM_GENERATOR", "llama")
    monkeypatch.delenv("LLM_MODEL_PATH", raising=False)
    with pytest.raises(RuntimeError):
        build_generator()


def test_환경변수로_llama_설정이_전달됨(monkeypatch, fake_llama):
    monkeypatch.setenv("LLM_GENERATOR", "llama")
    monkeypatch.setenv("LLM_MODEL_PATH", "x.gguf")
    monkeypatch.setenv("LLM_JSON_MODE", "schema")
    monkeypatch.setenv("LLM_REPEAT_PENALTY", "1.1")
    gen = build_generator()
    assert isinstance(gen, LlamaGenerator)
    assert gen.json_mode == "schema" and gen.repeat_penalty == 1.1
    init = dict(fake_llama[0][1])
    assert init["model_path"] == "x.gguf" and init["n_gpu_layers"] == -1


def test_off면_디코딩만_전달(req, fake_llama):
    gen = LlamaGenerator("x.gguf")
    gen.generate_light(req, build_light_messages(req))
    sent = fake_llama[-1][1]
    assert sent["temperature"] == 0.4 and sent["top_p"] == 0.9 and sent["max_tokens"] == 377
    assert "response_format" not in sent and "repeat_penalty" not in sent


def test_요청마다_seed가_다름(req, fake_llama):
    gen = LlamaGenerator("x.gguf")
    for _ in range(5):
        gen.generate_light(req, build_light_messages(req))
    seeds = {c[1]["seed"] for c in fake_llama if c[0] == "chat"}
    assert len(seeds) > 1


def test_schema면_값있는_후보만_허용하고_정확히_3개(req, fake_llama):
    del_req = req.model_copy(deep=True)
    del_req.delta_metrics.delta_comeback = None
    gen = LlamaGenerator("x.gguf", json_mode="schema", repeat_penalty=1.1)
    gen.generate_light(del_req, build_light_messages(del_req))
    sent = fake_llama[-1][1]
    schema = sent["response_format"]["schema"]
    items = schema["properties"]["highlights"]
    assert items["minItems"] == 3 and items["maxItems"] == 3
    assert "delta_comeback" not in items["items"]["properties"]["stat"]["enum"]
    assert sent["repeat_penalty"] == 1.1


def test_스키마_enum은_후보_11개_안에서만():
    schema = light_response_schema(list(CANDIDATE_KEYS))
    assert set(schema["properties"]["highlights"]["items"]["properties"]["stat"]["enum"]) <= set(CANDIDATE_KEYS)


def test_잘못된_json_mode는_에러(fake_llama):
    with pytest.raises(ValueError):
        LlamaGenerator("x.gguf", json_mode="strict")


def test_길이_초과는_ContextOverflowError로_바꿈(monkeypatch, req):
    class OverflowLlama:
        def __init__(self, **kwargs):
            pass

        def create_chat_completion(self, **kwargs):
            raise ValueError("Requested tokens (2668) exceed context window of 2048")

    monkeypatch.setitem(sys.modules, "llama_cpp", types.SimpleNamespace(Llama=OverflowLlama))
    gen = LlamaGenerator(model_path="fake.gguf")
    with pytest.raises(ContextOverflowError):
        gen.generate_light(req, build_light_messages(req))
