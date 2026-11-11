"""논문용 엔드포인트 (app/paper.py, /v1/paper/*) 테스트 — Mock 생성기."""
import hashlib
import json
import random
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app import light_comment as lc
from app import paper
from app.generator import ContextOverflowError, MockGenerator
from app.main import app
from app.prompt import HEAVY_PROMPT_VERSION

client = TestClient(app)
HEAVY = json.loads((Path(__file__).parent / "samples" / "heavy_chapters.json").read_text(encoding="utf-8"))
SENT = {"stat": "delta_inf_ds", "value": 0.31, "zone": "높음"}
SUMM = {"items": [{"stat": "delta_app", "value": 0.08, "zone": "높음"},
                  {"stat": "delta_opener", "value": 0.42, "zone": "높음"},
                  {"stat": "delta_vs_apm", "value": -0.2, "zone": "낮음"}]}


@pytest.fixture(autouse=True)
def _on(monkeypatch):
    monkeypatch.setenv("LLM_PAPER_ENDPOINT", "on")
    monkeypatch.setattr(main_module, "generator", MockGenerator())


def _req(condition="P1", seed=1, call_type="sentence", slot="pick1", payload=None):
    return {"condition": condition, "seed": seed, "call_type": call_type, "slot": slot,
            "payload": SENT if payload is None else payload}


def _heavy(ch):
    return {"chapter_id": ch, "data": HEAVY[ch]}


def test_꺼져_있으면_404(monkeypatch):
    monkeypatch.setenv("LLM_PAPER_ENDPOINT", "off")
    assert client.post("/v1/paper/generate", json=_req()).status_code == 404
    assert client.get("/v1/paper/meta").status_code == 404


def test_응답_형태():
    r = client.post("/v1/paper/generate", json=_req())
    assert r.status_code == 200
    j = r.json()
    for k in ("condition", "seed", "call_type", "slot", "messages", "prompt_version", "prompt_sha256", "rng_seed",
              "llama_seed", "decoding", "raw_text", "finish_reason", "prompt_tokens", "completion_tokens",
              "gen_sec", "wait_sec"):
        assert k in j
    assert j["llama_seed"] == 1
    assert j["decoding"] == {"temperature": 0.4, "top_p": 0.9, "max_tokens": 119, "repeat_penalty": 1.1}
    assert j["prompt_version"] == lc.LIGHT_COMMENT_VERSION
    assert j["prompt_sha256"] == paper.sha256_messages(j["messages"])
    assert j["raw_text"].startswith("[Mock]")


def test_같은_요청이면_프롬프트와_원문이_같음():
    a = client.post("/v1/paper/generate", json=_req(call_type="summary", slot="summary", payload=SUMM)).json()
    b = client.post("/v1/paper/generate", json=_req(call_type="summary", slot="summary", payload=SUMM)).json()
    assert a["messages"] == b["messages"] and a["rng_seed"] == b["rng_seed"] and a["raw_text"] == b["raw_text"]


def test_rng_seed_는_조건과_무관_시드와_payload_에_따라_다름():
    p1 = client.post("/v1/paper/generate", json=_req("P1")).json()
    p0 = client.post("/v1/paper/generate", json=_req("P0")).json()
    s2 = client.post("/v1/paper/generate", json=_req("P1", seed=2)).json()
    assert p1["rng_seed"] == p0["rng_seed"] != s2["rng_seed"]
    # payload 키 순서가 달라도 같은 값 (키 정렬 JSON)
    swapped = {"zone": "높음", "value": 0.31, "stat": "delta_inf_ds"}
    assert client.post("/v1/paper/generate", json=_req(payload=swapped)).json()["rng_seed"] == p1["rng_seed"]


def test_시드마다_예시_고정():
    """서비스는 시드 없는 Random → 예시가 바뀌지만, 논문용은 rng_seed 가 같으면 언제나 같다."""
    seen = {client.post("/v1/paper/generate", json=_req(seed=s)).json()["prompt_sha256"] for s in (1, 1, 1)}
    assert len(seen) == 1


def test_P0_는_규칙_방향_예시가_없고_데이터는_같음():
    p0 = client.post("/v1/paper/generate", json=_req("P0")).json()
    text = "\n".join(m["content"] for m in p0["messages"])
    assert p0["prompt_version"] == paper.P0_PROMPT_VERSION
    assert [m["role"] for m in p0["messages"]] == ["system", "user"]
    assert p0["messages"][0]["content"] == paper.P0_SENTENCE      # 11/10 메모 문구 그대로
    assert "방향:" not in text and "예시:" not in text and "규칙" not in text
    assert "Infinite Downstack 성향" in text and "+0.31" in text and "높음" in text
    p1 = client.post("/v1/paper/generate", json=_req("P1")).json()
    assert p0["decoding"] == p1["decoding"]


def test_FT_는_P1_과_같은_프롬프트():
    ft = client.post("/v1/paper/generate", json=_req("FT")).json()
    p1 = client.post("/v1/paper/generate", json=_req("P1")).json()
    assert ft["messages"] == p1["messages"] and ft["prompt_version"] == p1["prompt_version"]


@pytest.mark.parametrize("ch", paper.PAPER_CHAPTERS)
@pytest.mark.parametrize("cond", ["P0", "P1"])
def test_헤비_7챕터(ch, cond):
    r = client.post("/v1/paper/generate", json=_req(cond, call_type="heavy", slot=ch, payload=_heavy(ch)))
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["decoding"]["max_tokens"] == 119 and j["decoding"]["repeat_penalty"] == 1.1
    if cond == "P1":
        assert j["prompt_version"] == HEAVY_PROMPT_VERSION
    else:
        user = j["messages"][-1]["content"]
        assert "(상대보다" not in user and "[예시]" not in user


@pytest.mark.parametrize("body", [
    _req(slot="summary"),                                                       # sentence 인데 slot summary
    _req(call_type="heavy", slot="rivals", payload={"chapter_id": "rivals", "data": {}}),   # rivals 제외
    _req(call_type="heavy", slot="attack", payload={"chapter_id": "defense", "data": {}}),  # slot·chapter 불일치
    _req(payload={"stat": "delta_app", "value": 0.1, "zone": "없음"}),          # 영역 없음
    _req(payload={"stat": "nope", "value": 0.1}),                               # 모르는 stat
    _req(call_type="summary", slot="summary", payload={"items": [SUMM["items"][0], SUMM["items"][0]]}),
    _req(condition="P2"),
    _req(seed=-1),
])
def test_잘못된_요청은_422(body):
    assert client.post("/v1/paper/generate", json=body).status_code == 422


def test_입력_길이_초과는_413(monkeypatch):
    class Over(MockGenerator):
        def generate_raw(self, messages, decoding, seed):
            raise ContextOverflowError("exceed context window")
    monkeypatch.setattr(main_module, "generator", Over())
    assert client.post("/v1/paper/generate", json=_req()).status_code == 413


def test_서비스_엔드포인트는_그대로():
    r = client.post("/v1/light/sentence", json=SENT)
    assert r.status_code == 200 and "fallback" in r.json()


def test_meta():
    j = client.get("/v1/paper/meta").json()
    assert j["generator"] == "mock" and j["n_ctx"] == 2048 and j["repeat_penalty"] == 1.1
    assert set(j["prompt_versions"]) == {"P0", "P1_light", "P1_heavy"}


def test_rng_seed_는_sha256_앞_8바이트():
    req = paper.PaperRequest.model_validate(_req())
    key = paper.canonical_json({"seed": 1, "call_type": "sentence", "slot": "pick1", "payload": SENT})
    assert paper.rng_seed_of(req) == int.from_bytes(hashlib.sha256(key.encode()).digest()[:8], "big") & (2**63 - 1)
    random.Random(paper.rng_seed_of(req))   # Random 에 넣을 수 있는 정수


def test_P0_v2_값의_기준과_영역_표기():
    p0 = client.post("/v1/paper/generate", json=_req("P0")).json()
    user = p0["messages"][1]["content"]
    assert user.splitlines() == ["지표: Infinite Downstack 성향", "뜻: 다운스택(수비) 위주의 플레이 성향", "값: +0.31",
                                 "값의 기준: 맞붙은 상대와의 성향 차이(내 값 − 상대 값)",
                                 "영역(비슷한 실력대 기준): 높음"]
    s = client.post("/v1/paper/generate", json=_req("P0", call_type="summary", slot="summary", payload=SUMM)).json()
    assert ("- 블록당 공격력: 뜻: 블록 1개당 공격량. 적은 블록으로 많은 공격을 할수록 높음, 값 +0.08, "
            "값의 기준: 맞붙은 상대와의 차이(내 값 − 상대 값), 영역(비슷한 실력대 기준): 높음") in s["messages"][1]["content"]


@pytest.mark.parametrize("ch", ["tr_trend", "playstyle", "attack", "defense", "strength_split", "comeback_rate", "session_vs_slope"])
def test_P0_v2_heavy_값의_기준_부호(ch):
    r = client.post("/v1/paper/generate", json=_req("P0", call_type="heavy", slot=ch, payload=_heavy(ch))).json()
    lines = r["messages"][1]["content"].splitlines()[1:]
    counts = ("역전 기회", "역전승 횟수", "앞서던 경기")
    for line in lines:
        if line.startswith(tuple("- " + c for c in counts)):
            assert "+" not in line and "값의 기준" not in line
        else:
            assert "값의 기준: " in line, line
    assert "방향" not in r["messages"][1]["content"] and "[예시]" not in r["messages"][0]["content"]


def test_P0_v3_지표_뜻은_모든_지표에_있고_쓰는_법은_없음():
    from app.schemas import CANDIDATE_KEYS
    assert set(CANDIDATE_KEYS) <= set(paper.P0_MEANING)
    text = " ".join(paper.P0_MEANING.values())
    for rule in ("잘하고 못함", "성향입니다", "번 중", "앞섭니다", "밀립니다", "상대 대비", "짙", "옅"):
        assert rule not in text, rule


@pytest.mark.parametrize("ch", ["tr_trend", "playstyle", "attack", "defense", "strength_split", "session_vs_slope"])
def test_P0_v3_heavy_뜻(ch):
    r = client.post("/v1/paper/generate", json=_req("P0", call_type="heavy", slot=ch, payload=_heavy(ch))).json()
    for line in r["messages"][1]["content"].splitlines()[1:]:
        assert "(뜻: " in line, line
