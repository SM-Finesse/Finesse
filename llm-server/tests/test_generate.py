"""범용 생성 입구 POST /v1/generate (MSA CommentEngine, 11/17 지침서) — Mock 생성기."""
import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.generator import (
    ContextOverflowError,
    GenerationResult,
    GeneratorBusyError,
    MockGenerator,
)
from app.main import app

client = TestClient(app)
BODY = {"messages": [{"role": "system", "content": "한국어로 답하세요."},
                     {"role": "user", "content": "안녕하세요를 한 문장으로 답하세요."}],
        "max_tokens": 40}


@pytest.fixture(autouse=True)
def _mock(monkeypatch):
    monkeypatch.setattr(main_module, "generator", MockGenerator())


def test_200_고정_문장():
    r = client.post("/v1/generate", json=BODY)
    assert r.status_code == 200
    j = r.json()
    assert j["text"] == "모의 응답입니다." and j["finish_reason"] == "stop"
    assert isinstance(j["elapsed_ms"], int) and j["elapsed_ms"] >= 0


def test_messages_와_디코딩을_그대로_넘김(monkeypatch):
    seen = {}

    class Spy(MockGenerator):
        def generate_text(self, label, messages, decoding, lock_timeout=None, stop_at=None):
            seen.update(label=label, messages=messages, decoding=decoding, lock_timeout=lock_timeout, stop_at=stop_at)
            return GenerationResult(text="ok", finish_reason="length")

    monkeypatch.setattr(main_module, "generator", Spy())
    r = client.post("/v1/generate", json={**BODY, "temperature": 0.7, "top_p": 0.8})
    assert r.json()["finish_reason"] == "length"
    assert seen["label"] == "generic"
    assert seen["messages"] == BODY["messages"]          # 프롬프트를 고치거나 덧붙이지 않음
    assert seen["decoding"] == {"temperature": 0.7, "top_p": 0.8, "max_tokens": 40}
    assert seen["lock_timeout"] is None and seen["stop_at"] is None


def test_기본값(monkeypatch):
    seen = {}

    class Spy(MockGenerator):
        def generate_text(self, label, messages, decoding, lock_timeout=None, stop_at=None):
            seen.update(decoding)
            return GenerationResult(text="ok", finish_reason="stop")

    monkeypatch.setattr(main_module, "generator", Spy())
    client.post("/v1/generate", json=BODY)
    assert seen == {"temperature": 0.4, "top_p": 0.9, "max_tokens": 40}


@pytest.mark.parametrize("body", [
    {**BODY, "messages": []},                                         # messages 비었음
    {"max_tokens": 40},                                               # messages 없음
    {**BODY, "max_tokens": 0},
    {**BODY, "max_tokens": 513},
    {k: v for k, v in BODY.items() if k != "max_tokens"},             # max_tokens 필수
    {**BODY, "messages": [{"role": "tool", "content": "x"}]},         # role 3종만
    {**BODY, "messages": [{"role": "user"}]},
    {**BODY, "extra": 1},                                             # 모르는 칸
])
def test_형식이_틀리면_422(body):
    assert client.post("/v1/generate", json=body).status_code == 422


@pytest.mark.parametrize("exc, code", [
    (ContextOverflowError("exceed context window"), 413),
    (GeneratorBusyError("busy"), 503),
    (RuntimeError("boom"), 502),
])
def test_에러_코드(monkeypatch, exc, code):
    class Fail(MockGenerator):
        def generate_text(self, *a, **k):
            raise exc

    monkeypatch.setattr(main_module, "generator", Fail())
    assert client.post("/v1/generate", json=BODY).status_code == code


def test_기존_입구는_그대로():
    r = client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.08, "zone": "높음"})
    assert r.status_code == 200 and "fallback" in r.json()
