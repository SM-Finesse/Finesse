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
    HEAVY_CHAPTERS,
    HEAVY_DECODING,
    HEAVY_LABELS,
    build_heavy_messages,
)
from app.schemas import HEAVY_CHAPTER_IDS, HeavyRequest
from app.validation import OutputFormatError, parse_heavy_output, process_heavy_output

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

def test_8챕터_모두_프롬프트가_있음():
    assert set(HEAVY_CHAPTERS) == set(HEAVY_CHAPTER_IDS)


def test_시스템_프롬프트는_그_챕터_내용만():
    attack = build_heavy_messages(HeavyRequest.model_validate(body("attack")))[0]["content"]
    assert "공격 효율" in attack and "APP" in attack
    assert "Cheese Index 차이" not in attack and "역전" not in attack and "Opener" not in attack


def test_user_메시지에_영문_키가_없음():
    for ch in HEAVY_CHAPTER_IDS:
        req = HeavyRequest.model_validate(body(ch))
        user = build_heavy_messages(req)[1]["content"]
        assert user.startswith("챕터: " + HEAVY_CHAPTERS[ch]["title"])
        for key in HEAVY_LABELS:
            assert key not in user, (ch, key)


def test_user_메시지에_값이_그대로():
    user = build_heavy_messages(HeavyRequest.model_validate(body("defense")))[1]["content"]
    assert "- VS/APM 차이(공격 대비 수비 비율): -0.07" in user
    assert "- Cheese Index 차이(가비지 처리 성향): -2.56" in user


def test_라이벌은_승률과_배지를_서버가_계산():
    user = build_heavy_messages(HeavyRequest.model_validate(body("rivals")))[1]["content"]
    assert "- ab***_12: 15경기 5승 10패, 승률 33% (천적)" in user
    assert user.endswith("- te**er: 7경기 4승 3패, 승률 57%")  # 배지 없음("-")은 괄호 생략 (v3.1)


def _user(ch, data=None):
    req = HeavyRequest.model_validate({"chapter_id": ch, "data": CHAPTERS[ch] if data is None else data})
    return build_heavy_messages(req)[1]["content"]


def test_v31_부호_방향을_서버가_붙임():
    assert "- APP 차이(블록당 공격량): -0.05 (상대보다 낮음)" in _user("attack")
    assert "-2.56 (상대보다 낮음, 가비지 처리 성향이 약함)" in _user("defense")
    assert "+0.55 (라운드가 진행될수록 오름)" in _user("session_vs_slope")
    assert "-0.21 (강한 상대 구간 승률이 21%p 더 낮음)" in _user("strength_split")
    assert "+0.3 (상대보다 높음)" in _user("attack", {"delta_app": 0.3})
    assert "0 (상대와 같음)" in _user("attack", {"delta_app": 0})


def test_v31_플레이스타일은_강한_약한_성향으로_묶음():
    user = _user("playstyle")
    assert "- 상대보다 강한 성향: Opener(+0.1)" in user
    assert "- 상대보다 약한 성향: Plonk(-0.22), Stride(-0.08), Inf DS(-0.17)" in user
    assert "성향 차이" not in user


def test_v31_역전은_비교_줄을_붙임():
    assert _user("comeback_rate").endswith("- 비교: 역전승률이 역전 허용률보다 낮음")
    assert "비교" not in _user("comeback_rate", {"comeback_rate": 0.3})


def test_값이_없으면_값_없음():
    user = build_heavy_messages(HeavyRequest.model_validate({"chapter_id": "playstyle", "data": {}}))[1]["content"]
    assert user.endswith("- (값 없음)")


def test_예시_문장을_베끼면_형식_오류():
    from app.prompt import pick_heavy_example
    # 상황이 정해지지 않으면(값 없음) 숫자가 든 챕터 기본 예시를 쓴다 → 그대로 베끼면 거절
    first = pick_heavy_example("attack", {}).split(". ")[0] + "."
    with pytest.raises(OutputFormatError):
        process_heavy_output(first, "attack", {})


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

def test_Mock은_문장만_쓰고_서버가_JSON을_만듦():
    req = HeavyRequest.model_validate(body("rivals"))
    result = MockGenerator().generate_heavy(req, build_heavy_messages(req))
    resp = process_heavy_output(result.text, "rivals", req.data)
    assert resp.chapter_id == "rivals" and resp.footnote.startswith("[Mock]")


# ---------- heavy-draft-v2: 문장 → {chapter_id, footnote} ----------

ATTACK = CHAPTERS["attack"]


def test_문장만_오면_서버가_조립():
    resp = process_heavy_output("블록당 공격량이 상대보다 적습니다.", "attack", ATTACK)
    assert resp.chapter_id == "attack" and resp.footnote == "블록당 공격량이 상대보다 적습니다."


def test_예전_JSON_형식도_받음():
    resp = process_heavy_output(out(footnote="공격 효율이 낮습니다."), "attack", ATTACK)
    assert resp.footnote == "공격 효율이 낮습니다."


@pytest.mark.parametrize("text, expected", [
    ('"공격 효율이 낮습니다."', "공격 효율이 낮습니다."),                          # 따옴표
    ("### 각주\n공격 효율이 낮습니다.", "공격 효율이 낮습니다."),                  # 머리말
    ('"attack" 챕터의 각주: 공격 효율이 낮습니다.', "공격 효율이 낮습니다."),       # 머리말
    ("첫 문장입니다. 둘째 문장입니다. 셋째 문장입니다.", "첫 문장입니다. 둘째 문장입니다."),  # 2문장까지
    ("완성된 문장입니다. 잘린 문장", "완성된 문장입니다."),                        # 출력 한도에서 잘림
    ("첫 문단입니다.\n\n두 번째 문단입니다.", "첫 문단입니다."),                  # 첫 문단만
    ("APP가 0.05 낮습니다. TETR.IO 기준입니다.", "APP가 0.05 낮습니다. TETR.IO 기준입니다."),  # 소수점·약어
    ("- 공격 효율이 낮습니다.\n* 수비도 낮습니다.", "공격 효율이 낮습니다. 수비도 낮습니다."),  # 목록 기호 (v3.1)
    ("-0.05 만큼 낮습니다.", "-0.05 만큼 낮습니다."),                            # 음수 부호는 유지
])
def test_문장_다듬기(text, expected):
    assert process_heavy_output(text, "attack", ATTACK).footnote == expected


@pytest.mark.parametrize("text", [
    "",                                                        # 빈 출력
    "끝나지 않은 문장",                                         # 완성된 문장 없음
    "당신은 테트리스 게임 TETR.IO의 전적 데이터를 분석합니다.",    # 프롬프트 베낌
    "防御 성능은 약간 약합니다.",                                # 중국어
    "delta_app 값이 음수이므로 공격이 약합니다.",                 # 키 이름 노출
    "weighted_app의 절대값이 큽니다.",                           # 변수 이름 꼴
])
def test_v2_형식_오류(text):
    with pytest.raises(OutputFormatError):
        process_heavy_output(text, "attack", ATTACK)


def test_라이벌_마스킹_닉네임은_허용():
    data = CHAPTERS["rivals"]
    resp = process_heavy_output("ab***_12 상대에게 15경기 중 10번 졌습니다.", "rivals", data)
    assert "ab***_12" in resp.footnote


def test_라이벌_키_이름은_거절():
    with pytest.raises(OutputFormatError):
        process_heavy_output("nickname_masked 상대에게 자주 졌습니다.", "rivals", CHAPTERS["rivals"])


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


def test_heavy는_JSON_모드여도_response_format_없음(fake_llama):
    # heavy-draft-v2 는 문장만 쓰므로 JSON 강제를 걸지 않는다
    req = HeavyRequest.model_validate(body("defense"))
    LlamaGenerator("x.gguf", json_mode="schema").generate_heavy(req, build_heavy_messages(req))
    assert "response_format" not in fake_llama[-1]


# ---------- 엔드포인트 ----------

@pytest.mark.parametrize("chapter_id", HEAVY_CHAPTER_IDS)
def test_heavy_엔드포인트_200(chapter_id):
    r = client.post("/v1/comment/heavy-chapter", json=body(chapter_id))
    assert r.status_code == 200
    assert r.json()["chapter_id"] == chapter_id and r.json()["footnote"]


def test_heavy_잘못된_챕터는_422():
    r = client.post("/v1/comment/heavy-chapter", json={"chapter_id": "summary", "data": {}})
    assert r.status_code == 422


class BadGenerator:
    name = "bad"

    def __init__(self):
        self.calls = 0

    def generate_heavy(self, req, messages):
        from app.generator import GenerationResult
        self.calls += 1
        return GenerationResult(text="防御 성능이 약합니다.", finish_reason="stop")


def test_heavy_형식_오류는_502(monkeypatch):
    # 대체 문장을 끄면 예전처럼 502 (백엔드가 재요청)
    monkeypatch.setenv("LLM_HEAVY_FALLBACK", "off")
    monkeypatch.setattr(main_module, "generator", BadGenerator())
    r = client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert r.status_code == 502


def test_v34_모두_실패하면_대체_문장으로_200(monkeypatch):
    bad = BadGenerator()
    monkeypatch.setattr(main_module, "generator", bad)
    monkeypatch.delenv("LLM_HEAVY_FALLBACK", raising=False)
    monkeypatch.delenv("LLM_HEAVY_ATTEMPTS", raising=False)
    r = client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert r.status_code == 200 and bad.calls == 2  # 기본 2번 시도 후 대체
    assert r.json()["chapter_id"] == "attack" and "APP" in r.json()["footnote"]


def test_v34_시도_횟수_설정(monkeypatch):
    bad = BadGenerator()
    monkeypatch.setattr(main_module, "generator", bad)
    monkeypatch.setenv("LLM_HEAVY_ATTEMPTS", "3")
    client.post("/v1/comment/heavy-chapter", json=body("attack"))
    assert bad.calls == 3


def test_v35_라이벌은_기본으로_LLM_없이_서버_문장(monkeypatch):
    bad = BadGenerator()
    monkeypatch.setattr(main_module, "generator", bad)
    monkeypatch.delenv("LLM_HEAVY_TEMPLATE_CHAPTERS", raising=False)
    r = client.post("/v1/comment/heavy-chapter", json=body("rivals"))
    assert r.status_code == 200 and bad.calls == 0
    assert "ab***_12에게는" in r.json()["footnote"]


def test_v35_서버_문장_챕터를_비우면_라이벌도_LLM(monkeypatch):
    bad = BadGenerator()
    monkeypatch.setattr(main_module, "generator", bad)
    monkeypatch.setenv("LLM_HEAVY_TEMPLATE_CHAPTERS", "")
    client.post("/v1/comment/heavy-chapter", json=body("rivals"))
    assert bad.calls == 2


def test_v35_상대_강도_퍼센트를_퍼센트포인트로():
    data = CHAPTERS["strength_split"]  # -0.21
    resp = process_heavy_output("강한 상대 구간의 승률이 약한 상대 구간보다 21% 낮습니다.", "strength_split", data)
    assert resp.footnote == "강한 상대 구간의 승률이 약한 상대 구간보다 21%p 낮습니다."
    with pytest.raises(OutputFormatError):  # 값과 다른 숫자는 고치지 않고 거절
        process_heavy_output("강한 상대 구간의 승률이 8% 낮습니다.", "strength_split", data)


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


# ---------- v3.2 내용 검사 (10/12 25번 실측 출력에서 가져온 문장) ----------

@pytest.mark.parametrize("ch, text", [
    ("playstyle", "상대보다 강한 성향은 Opener입니다. 이는 Opener 성향이 상대보다 0.13점 높습니다."),  # 예시 값 베낌
    ("strength_split", "강한 상대 구간의 승률이 약한 상대 구간보다 0.21% 낮습니다."),               # 비율에 %
    ("attack", "블록당 공격량(APP)이 상대보다 낮습니다(-0.05). Weighted APP도 함께 높아 공격 효율이 더욱 낮아졌습니다(-1.34)."),
    ("defense", "Cheese Index 차이(-2.56)는 상대보다 높아 가비지 처리에 치중합니다."),               # 부호 반대
    ("rivals", "ab***_12: 15경기에서 3승, 10패를 기록하며 승률이 33%로 천적입니다."),               # 승수 틀림
    ("rivals", "ab***_12는 천적입니다. te**er: 7경기에서 4승을 거두며 승률이 57%로 우세합니다."),  # 배지 오용
    ("strength_split", "보통 음수이며, 음수 폭이 클수록 강한 상대에게 크게 무너진다는 뜻입니다."),    # 규칙 베낌
])
def test_v32_내용_오류는_502(ch, text):
    with pytest.raises(OutputFormatError):
        process_heavy_output(text, ch, CHAPTERS[ch])


@pytest.mark.parametrize("ch, text", [
    ("tr_trend", "최근 TR이 전체 기간 평균보다 62.87점 낮습니다. 최근 TR이 전체 평균보다 약 63점 낮은 하락세입니다."),
    ("attack", "블록당 공격량(APP)이 상대보다 낮습니다(-0.05). Weighted APP도 함께 낮아 공격 효율이 더욱 떨어집니다(-1.34)."),
    ("defense", "VS/APM 차이(-0.07)는 상대보다 수비 비중이 작습니다. Cheese Index 차이(-2.56)는 상대보다 가비지 처리 성향이 약합니다."),
    ("strength_split", "강한 상대 구간 승률이 약한 상대 구간보다 21%p 낮습니다."),
    ("comeback_rate", "역전승률(0.25)이 역전 허용률(0.4)보다 낮습니다. 12번 중 3번 역전했습니다."),
    ("rivals", "ab***_12는 15경기 중 5승을 거두었지만, 승률이 33%로 천적에 해당합니다. 반면 te**er는 7경기에서 4승을 따냈습니다."),
])
def test_v32_맞는_문장은_통과(ch, text):
    assert process_heavy_output(text, ch, CHAPTERS[ch]).footnote


# ---------- v3.3 상황별 예시 ----------
import json as _json
from pathlib import Path as _Path

from app.prompt import pick_heavy_example

POS = {k: v for k, v in _json.loads((_Path(__file__).parent / "samples" / "heavy_chapters_pos.json")
                                     .read_text(encoding="utf-8")).items() if not k.startswith("_")}


@pytest.mark.parametrize("ch, data, must", [
    ("attack", {"delta_app": -0.05, "delta_weighted_app": -1.34}, "Weighted APP도 상대보다 낮아"),
    ("attack", {"delta_app": 0.06, "delta_weighted_app": -0.8}, "하지만 Weighted APP는 상대보다 낮아"),
    ("attack", {"delta_app": 0.06, "delta_weighted_app": 0.8}, "Weighted APP도 상대보다 높아"),
    ("defense", {"delta_vs_apm": 0.11, "delta_cheese_index": 1.9}, "Cheese Index도 상대보다 높아"),
    ("tr_trend", {"tr_trend_delta": 85.3}, "상승세"),
    ("tr_trend", {"tr_trend_delta": -62.87}, "하락세"),
    ("strength_split", {"strength_split": 0.08}, "높습니다"),
    ("session_vs_slope", {"session_vs_slope": -0.4}, "내립니다"),
    ("comeback_rate", {"comeback_rate": 0.45, "comeback_rate_against": 0.2}, "보다 높습니다"),
    ("playstyle", {"delta_opener": 0.1, "delta_plonk": 0.2}, "Opener 성향이 강합니다"),
])
def test_v33_입력_방향과_같은_예시를_고름(ch, data, must):
    assert must in pick_heavy_example(ch, data)


def test_v33_라이벌_예시는_입력_순서대로_배지를_맞춤():
    ex = pick_heavy_example("rivals", CHAPTERS["rivals"])  # 천적, 배지 없음
    assert "천적" in ex.split(". ")[0] and "우세" not in ex and "접전" not in ex
    ex = pick_heavy_example("rivals", POS["rivals"])  # 우세(70%), 접전(50%)
    assert "우세" in ex and "접전" in ex and "천적" not in ex


def test_v33_시스템_프롬프트에_고른_예시가_들어감():
    system = build_heavy_messages(HeavyRequest.model_validate({"chapter_id": "attack", "data": POS["attack"]}))[0]["content"]
    assert pick_heavy_example("attack", POS["attack"]) in system


def test_v33_예시의_가짜_닉네임을_베끼면_502():
    with pytest.raises(OutputFormatError):
        process_heavy_output("xy***_7에게는 밀리는 편입니다.", "rivals", CHAPTERS["rivals"])


@pytest.mark.parametrize("ch", list(POS))
def test_v33_양수_샘플도_요청_통과(ch):
    assert build_heavy_messages(HeavyRequest.model_validate({"chapter_id": ch, "data": POS[ch]}))


# ---------- v3.4 대체 문장·검사 ----------
import random as _random

from app.fallback import NO_DATA, build_fallback


@pytest.mark.parametrize("sample", ["neg", "pos"])
@pytest.mark.parametrize("ch", list(POS))
@pytest.mark.parametrize("seed", range(4))
def test_v34_대체_문장은_검사를_통과(sample, ch, seed):
    data = (CHAPTERS if sample == "neg" else POS)[ch]
    text = build_fallback(ch, data, _random.Random(seed))
    resp = process_heavy_output(text, ch, data)
    assert resp.footnote == text and text != NO_DATA


@pytest.mark.parametrize("ch", list(POS))
def test_v34_값이_없으면_대체_문장도_값_없음_안내(ch):
    text = build_fallback(ch, {})
    assert text in (NO_DATA, "5경기 이상 만난 상대가 아직 없습니다.")
    process_heavy_output(text, ch, {})


def test_v34_대체_문장_내용():
    rng = _random.Random(0)
    assert "21%p 낮습니다" in build_fallback("strength_split", CHAPTERS["strength_split"], rng)
    assert "ab***_12에게는 15경기 5승 10패(승률 33%)로 내가 자주 져서 천적입니다." in build_fallback("rivals", CHAPTERS["rivals"])
    assert "te**er와는 7경기 4승 3패(승률 57%)입니다." in build_fallback("rivals", CHAPTERS["rivals"])
    text = build_fallback("attack", POS["attack"], rng)
    assert "높습니다(+0.06)" in text and "Weighted APP는 상대보다 낮아" in text


@pytest.mark.parametrize("ch, text", [
    ("tr_trend", "최근 TR이 전체 평균보다 62.87점 낮습니다. 이는 최근 TR이 전체 평균보다 높은 상승세를 나타냅니다."),
    ("strength_split", "가장 약한 상대 구간에서 가장 강한 상대 구간보다 승률이 8% 더 높다는 의미입니다."),
    ("defense", "VS/APM 차이(-0.07)가 음수면 상대보다 수비 비중이 작습니다."),
    ("rivals", "ab***_12는 15경기 5승으로 천적입니다. te**er는 접전하는 선수입니다."),
    ("defense", "공격 대비 수비 비율(VS/APM)이 상대보다 낮습니다. 이는 상대적으로 공격에 치중하는 플레이어를 나타냅니다."),
])
def test_v34_추가_검사(ch, text):
    data = POS[ch] if ch == "strength_split" else CHAPTERS[ch]
    with pytest.raises(OutputFormatError):
        process_heavy_output(text, ch, data)


def test_v34_예시에는_숫자가_없음():
    for data in (CHAPTERS, POS):
        for ch, d in data.items():
            ex = pick_heavy_example(ch, d)
            assert not any(c.isdigit() for c in ex.replace("xy***_7", "").replace("Inf DS", "")), (ch, ex)
