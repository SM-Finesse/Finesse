"""heavy 챕터 (스키마·프롬프트·생성기·응답 검증·엔드포인트) 테스트.

근거: LLM/AI 파트 설계 v1.2 5.3절(입출력·형식 오류), 7.2절(max_tokens 119), 7.3절·7.4절(프롬프트)
실행: llm-server 폴더에서  python -m pytest -v
"""
import json
import sys
import types
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import app.main as main_module
from app.generator import ContextOverflowError, LlamaGenerator, MockGenerator
from app.main import app
from app.prompt import (
    HEAVY_DECODING,
    HEAVY_SYSTEM_PROMPT,
    RIVALS_GUIDE,
    build_heavy_messages,
)
from app.schemas import HEAVY_CHAPTER_IDS, HeavyRequest
from app.validation import OutputFormatError, parse_heavy_output

SAMPLE = Path(__file__).parent / "samples" / "heavy_chapters.json"
CHAPTERS = {k: v for k, v in json.loads(SAMPLE.read_text(encoding="utf-8")).items() if not k.startswith("_")}
client = TestClient(app)


def body(chapter_id):
    return {"chapter_id": chapter_id, "data": CHAPTERS[chapter_id]}


def out(chapter_id="attack", footnote="공격 효율이 상대보다 낮습니다(-0.05)."):
    return json.dumps({"chapter_id": chapter_id, "footnote": footnote}, ensure_ascii=False)


# ---------- 요청 스키마 ----------

def test_샘플은_8챕터_모두_있음():
    assert tuple(CHAPTERS) == HEAVY_CHAPTER_IDS


@pytest.mark.parametrize("chapter_id", HEAVY_CHAPTER_IDS)
def test_8챕터_요청_통과(chapter_id):
    HeavyRequest.model_validate(body(chapter_id))


def test_빈_data도_통과():
    # 백엔드는 값이 없으면 빈 객체를 보낸다 (playstyle·attack·defense)
    HeavyRequest.model_validate({"chapter_id": "playstyle", "data": {}})


@pytest.mark.parametrize("bad", [
    {"chapter_id": "summary", "data": {}},                    # 8개 밖 챕터
    {"chapter_id": "attack"},                                 # data 없음
    {"chapter_id": "attack", "data": [1, 2]},                 # data 가 객체 아님
    {"chapter_id": "attack", "data": {}, "partial": True},    # 정의 안 된 필드
])
def test_잘못된_요청은_거절(bad):
    with pytest.raises(ValidationError):
        HeavyRequest.model_validate(bad)


# ---------- 프롬프트 ----------

def test_user_메시지는_요청과_같은_JSON():
    messages = build_heavy_messages(HeavyRequest.model_validate(body("comeback_rate")))
    assert [m["role"] for m in messages] == ["system", "user"]
    assert json.loads(messages[1]["content"]) == body("comeback_rate")


def test_라이벌_챕터만_지침이_붙음():
    rivals = build_heavy_messages(HeavyRequest.model_validate(body("rivals")))[0]["content"]
    attack = build_heavy_messages(HeavyRequest.model_validate(body("attack")))[0]["content"]
    assert rivals.startswith(HEAVY_SYSTEM_PROMPT) and RIVALS_GUIDE in rivals
    assert attack == HEAVY_SYSTEM_PROMPT


def test_heavy_디코딩():
    assert HEAVY_DECODING == {"temperature": 0.4, "top_p": 0.9, "max_tokens": 119}


# ---------- 응답 검증 (v1.2 5.3절 형식 오류 4가지) ----------

def test_정상_출력():
    assert parse_heavy_output(out(), "attack").footnote.startswith("공격")


@pytest.mark.parametrize("text", [
    "각주: 공격 효율이 낮습니다.",                               # 유효한 JSON 아님
    json.dumps({"chapter_id": "attack"}),                      # footnote 없음
    json.dumps({"footnote": "문장"}),                           # chapter_id 없음
    out(chapter_id="defense"),                                 # chapter_id 불일치
    out(footnote="   "),                                       # footnote 공백
    json.dumps({"chapter_id": "attack", "footnote": "s", "sentence": "s"}),  # 정해지지 않은 키
])
def test_형식_오류(text):
    with pytest.raises(OutputFormatError):
        parse_heavy_output(text, "attack")


# ---------- 생성기 ----------

def test_Mock은_요청_chapter_id를_그대로():
    req = HeavyRequest.model_validate(body("rivals"))
    result = MockGenerator().generate_heavy(req, build_heavy_messages(req))
    assert parse_heavy_output(result.text, "rivals").footnote.startswith("[Mock]")


@pytest.fixture
def fake_llama(monkeypatch):
    calls = []

    class FakeLlama:
        def __init__(self, **kwargs):
            pass

        def create_chat_completion(self, **kwargs):
            calls.append(kwargs)
            return {"choices": [{"message": {"content": out()}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1}}

    monkeypatch.setitem(sys.modules, "llama_cpp", types.SimpleNamespace(Llama=FakeLlama))
    return calls


def test_llama_heavy는_max_tokens_119(fake_llama):
    req = HeavyRequest.model_validate(body("attack"))
    LlamaGenerator("x.gguf").generate_heavy(req, build_heavy_messages(req))
    assert fake_llama[-1]["max_tokens"] == 119
    assert "response_format" not in fake_llama[-1]


def test_schema_모드는_chapter_id를_요청값으로_제한(fake_llama):
    req = HeavyRequest.model_validate(body("defense"))
    LlamaGenerator("x.gguf", json_mode="schema").generate_heavy(req, build_heavy_messages(req))
    schema = fake_llama[-1]["response_format"]["schema"]
    assert schema["properties"]["chapter_id"]["enum"] == ["defense"]


# ---------- 엔드포인트 ----------

@pytest.mark.parametrize("chapter_id", HEAVY_CHAPTER_IDS)
def test_heavy_엔드포인트_200(chapter_id):
    r = client.post("/v1/comment/heavy-chapter", json=body(chapter_id))
    assert r.status_code == 200
    assert r.json()["chapter_id"] == chapter_id and r.json()["footnote"]


def test_heavy_잘못된_챕터는_422():
    r = client.post("/v1/comment/heavy-chapter", json={"chapter_id": "summary", "data": {}})
    assert r.status_code == 422


def test_heavy_형식_오류는_502(monkeypatch):
    class BadGenerator:
        name = "bad"

        def generate_heavy(self, req, messages):
            from app.generator import GenerationResult
            return GenerationResult(text=out(chapter_id="defense"), finish_reason="stop")

    monkeypatch.setattr(main_module, "generator", BadGenerator())
    r = client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert r.status_code == 502


def test_heavy_길이_초과는_413(monkeypatch):
    class OverflowGenerator:
        name = "overflow"

        def generate_heavy(self, req, messages):
            raise ContextOverflowError("exceed context window")

    monkeypatch.setattr(main_module, "generator", OverflowGenerator())
    r = client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert r.status_code == 413


def test_응답에_charset_utf8():
    r = client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert r.headers["content-type"] == "application/json; charset=utf-8"
