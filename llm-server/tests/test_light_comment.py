"""라이트뷰 개별·기본 코멘트 (light-v5, 31번 10/16 개정판 2.1~2.5절) 테스트.

실행: llm-server 폴더에서  python -m pytest tests/test_light_comment.py -v
"""
import random

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app import light_comment as lc
from app.generator import GenerationResult, MockGenerator
from app.light6 import NAMES
from app.main import app

client = TestClient(app)
KEYS = list(NAMES)

# (stat, value, zone) — 31번 2.3절 Q1·Q3 기준으로 맞는 영역
CASES = [
    ("delta_opener", 0.42, "높음"), ("delta_opener", -0.40, "낮음"), ("delta_opener", 0.05, "보통"),
    ("delta_opener", -0.05, "보통"), ("delta_opener", 0.0, "보통"),
    ("delta_plonk", 0.26, "높음"), ("delta_stride", -0.30, "낮음"), ("delta_inf_ds", 0.10, "보통"),
    ("delta_app", 0.08, "높음"), ("delta_app", -0.09, "낮음"), ("delta_app", 0.01, "보통"), ("delta_app", -0.02, "보통"),
    ("delta_weighted_app", 0.05, "높음"), ("delta_weighted_app", -0.01, "보통"),
    ("delta_vs_apm", 0.25, "높음"), ("delta_vs_apm", -0.20, "낮음"), ("delta_vs_apm", 0.04, "보통"),
    ("delta_cheese_index", 25.3, "높음"), ("delta_cheese_index", -12.0, "낮음"), ("delta_cheese_index", 3.4, "보통"),
    ("strength_split", -0.10, "높음"), ("strength_split", 0.12, "높음"), ("strength_split", -0.25, "보통"),
    ("strength_split", -0.55, "낮음"), ("strength_split", 0.0, "높음"),
    ("delta_comeback", 0.12, "높음"), ("delta_comeback", -0.10, "낮음"), ("delta_comeback", 0.02, "보통"),
    ("delta_comeback", -0.03, "보통"),
    ("session_vs_slope", 2.1, "높음"), ("session_vs_slope", -0.3, "낮음"), ("session_vs_slope", 0.6, "보통"),
    ("session_vs_slope", 0.0, "보통"),
]


def test_영역은_calc_값을_쓰고_없으면_직접_판정():
    assert lc.resolve_zone("delta_app", 0.01, "높음") == "높음"
    assert lc.resolve_zone("delta_app", 0.08, None) == "높음"
    assert lc.resolve_zone("delta_app", 0.01, None) == "보통"
    assert lc.resolve_zone("strength_split", -0.10, None) == "높음"


def test_값_자리수():
    assert lc.fmt_value("delta_app", 0.0123) == "+0.01"
    assert lc.fmt_value("delta_cheese_index", 13.86) == "+13.9"
    assert lc.fmt_value("delta_app", 0.001) == "0.00"
    assert lc.state("delta_app", 0.001, "보통") == "zero"


def test_strength_영역과_부호가_반대면_hold():
    assert lc.state("strength_split", -0.10, "높음") == "hold"
    assert lc.state("strength_split", 0.12, "높음") == "high"
    assert lc.state("strength_split", -0.25, "보통") == "mid-"


@pytest.mark.parametrize("k,v,z", CASES)
@pytest.mark.parametrize("phrase", lc.COMPARE_PHRASES)
def test_대체_문장은_검사_통과(k, v, z, phrase):
    if lc.state(k, v, z) == "zero" and phrase not in lc._ZERO_PHRASES:
        pytest.skip("0 이면 '…보다' 표현은 쓰지 않음")
    text = lc.build_sentence_text(k, v, z, phrase)
    assert lc.check_sentence(text, k, v, z) is None, text
    assert lc.fmt_value(k, v) in text


@pytest.mark.parametrize("seed", range(20))
def test_대체_요약은_검사_통과(seed):
    rng = random.Random(seed)
    items = rng.sample(CASES, 3)
    while len({k for k, _, _ in items}) < 3:
        items = rng.sample(CASES, 3)
    text = lc.build_summary_text(items, rng)
    assert lc.check_summary(text, items) is None, text


@pytest.mark.parametrize("k,v,z", CASES)
def test_프롬프트에_판정_용어가_없음(k, v, z):
    msgs = lc.build_sentence(k, v, z, random.Random(0))
    user = msgs[1]["content"]
    for w in ("보통", "범위", "영역", "높음", "낮음", "Q1", "Q3"):
        assert w not in user
    assert user.startswith(f"지표: {NAMES[k]}")
    assert "예시: " in user and lc.fmt_value(k, v) in user
    if k in lc.COMPARE_KEYS:
        assert any(f"'{p}'" in msgs[0]["content"] for p in lc.COMPARE_PHRASES)


def test_요약_프롬프트():
    items = [("delta_app", 0.08, "높음"), ("delta_opener", 0.05, "보통"), ("strength_split", -0.10, "높음")]
    msgs = lc.build_summary(items, random.Random(0))
    assert "보통" not in msgs[1]["content"] and "높음" not in msgs[1]["content"]
    assert msgs[1]["content"].count("- ") >= 3


@pytest.mark.parametrize("text,k,v,z,why", [
    ("상대 대비 블록당 공격량(APP)에서 크게 앞서지만 차이는 크지 않습니다.", "delta_app", 0.01, "보통", "강조"),
    ("상대 대비 블록당 공격량(APP)이 크지 않습니다.", "delta_app", 0.08, "높음", "흐림"),          # 11번 10/19 사례
    ("상대 대비 블록당 공격량(APP)은 비슷한 수준입니다(+0.08).", "delta_app", 0.08, "높음", "흐림"),
    ("블록당 공격량(APP)에서 앞섭니다.", "delta_app", 0.08, "높음", "비교 기준 누락"),
    ("블록당 공격량(APP)이 상대적으로 앞섭니다.", "delta_app", 0.08, "높음", "상대적"),
    ("상대 대비 블록당 공격량(APP)에서 밀립니다.", "delta_app", 0.08, "높음", "방향 반대"),
    ("상대 대비 블록당 공격량(APP)은 보통 수준입니다.", "delta_app", 0.01, "보통", "판정 정보"),
    ("상대 대비 Opener 성향에서 앞섭니다.", "delta_opener", 0.42, "높음", "우열"),
    ("상대 대비 Opener 성향이 짙게 나타납니다(+0.35).", "delta_opener", 0.42, "높음", "입력에 없는 숫자"),
    ("상대 대비 Opener 성향이 12% 짙게 나타납니다.", "delta_opener", 0.42, "높음", "백분율"),
    ("상대 대비 Plonk 성향이 짙게 나타납니다.", "delta_opener", 0.42, "높음", "지표 빠짐"),
    ("강한 상대를 만났을 때의 승률이 크게 낮습니다.", "strength_split", -0.10, "높음", "강조"),
    ("강한 상대를 만났을 때의 승률이 오히려 높습니다.", "strength_split", -0.25, "보통", "방향 반대"),
    ("강한 상대를 만나도 잘 버팁니다.", "strength_split", -0.55, "낮음", "방향 반대"),
    ("상대보다 라운드가 진행될수록 공방 능력(VS)이 강해집니다.", "session_vs_slope", 2.1, "높음", "상대"),
    ("라운드가 진행되어도 공방 능력(VS)은 그대로입니다.", "session_vs_slope", 0.0, "보통", "추세"),
    ("유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강합니다.", "delta_comeback", 0.12, "높음", "방향 반대"),
    ("최근 5경기 상대 대비 블록당 공격량(APP)에서 앞섭니다.", "delta_app", 0.08, "높음", ""),
])
def test_개별_검사_거부(text, k, v, z, why):
    got = lc.check_sentence(text, k, v, z)
    assert got is not None and why in got, got


@pytest.mark.parametrize("text,k,v,z", [
    ("상대 대비 블록당 공격량(APP)은 비슷한 수준입니다(+0.01).", "delta_app", 0.01, "보통"),   # 31번 2.2절 예시
    ("맞붙은 상대들에 비해 블록당 공격량(APP)이 조금 앞서지만 차이는 크지 않습니다.", "delta_app", 0.01, "보통"),
    ("TETR.IO 대전에서 상대 대비 블록당 공격량(APP)에서 앞섭니다(+0.08).", "delta_app", 0.08, "높음"),
    ("강한 상대를 만나도 승률이 크게 떨어지지 않습니다.", "strength_split", -0.10, "높음"),
    ("라운드가 진행되어도 공방 능력(VS)에 뚜렷한 추세가 없습니다.", "session_vs_slope", 0.0, "보통"),
])
def test_개별_검사_허용(text, k, v, z):
    assert lc.check_sentence(text, k, v, z) is None


def test_요약_검사():
    items = [("delta_app", 0.01, "보통"), ("delta_opener", 0.05, "보통"), ("delta_vs_apm", -0.02, "보통")]
    ok = "상대 대비 블록당 공격량(APP)과 Opener 성향은 비슷한 수준이고, 수비 비중(VS/APM)도 차이가 크지 않습니다."
    assert lc.check_summary(ok, items) is None
    assert "강조" in lc.check_summary(ok.replace("비슷한 수준이고", "크게 앞서고"), items)
    assert "비교 기준" in lc.check_summary(ok.replace("상대 대비 ", ""), items)
    assert "지표 빠짐" in lc.check_summary("상대 대비 블록당 공격량(APP)과 Opener 성향은 비슷한 수준입니다.", items)
    assert "승률" in lc.check_summary(ok[:-1] + "며 승률도 비슷합니다.", items)


# ---------------- 엔드포인트 ----------------
class Scripted:
    name = "scripted"

    def __init__(self, *outs):
        self.outs, self.labels = list(outs), []

    def generate_text(self, label, messages, decoding, **kwargs):
        self.labels.append(label)
        return GenerationResult(text=self.outs.pop(0), finish_reason="stop")


@pytest.fixture
def mock(monkeypatch):
    monkeypatch.setattr(main_module, "generator", MockGenerator())


def test_엔드포인트_Mock(mock):
    r = client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.08, "zone": "높음"})
    assert r.status_code == 200 and r.json()["fallback"] is False and "앞섭니다" in r.json()["sentence"]
    r = client.post("/v1/light/sentence", json={"stat": "strength_split", "value": -0.10})       # 영역 없음 → 직접 판정
    assert r.status_code == 200 and "떨어지지 않" in r.json()["sentence"]
    items = [{"stat": "delta_app", "value": 0.08, "zone": "높음"}, {"stat": "delta_opener", "value": 0.05, "zone": "보통"},
             {"stat": "session_vs_slope", "value": 0.6, "zone": "보통"}]
    r = client.post("/v1/light/summary", json={"items": items})
    assert r.status_code == 200 and r.json()["fallback"] is False and r.json()["light_summary"]


def test_엔드포인트_재시도_후_대체(monkeypatch):
    gen = Scripted("상대 대비 블록당 공격량(APP)이 크지 않습니다.", "상대 대비 블록당 공격량(APP)에서 앞섭니다.")
    monkeypatch.setattr(main_module, "generator", gen)
    r = client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.08, "zone": "높음"})
    assert r.json() == {"stat": "delta_app", "sentence": "상대 대비 블록당 공격량(APP)에서 앞섭니다.", "fallback": False}
    assert gen.labels == ["light5:sentence", "light5:sentence"]
    monkeypatch.setattr(main_module, "generator", Scripted("Bad.", "Bad."))
    r = client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.08, "zone": "높음"})
    assert r.json()["fallback"] is True and r.json()["sentence"].endswith("앞섭니다(+0.08).")


def test_엔드포인트_422(mock):
    assert client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.1, "zone": "없음"}).status_code == 422
    items = [{"stat": "delta_app", "value": 0.1}, {"stat": "delta_app", "value": 0.2}]
    assert client.post("/v1/light/summary", json={"items": items}).status_code == 422


def test_시간_예산(monkeypatch):
    clock = {"t": 100.0}
    monkeypatch.setattr(main_module.time, "monotonic", lambda: clock["t"])

    class Slow:
        name = "slow"
        calls = 0

        def generate_text(self, label, messages, decoding, **kwargs):
            self.calls += 1
            self.kwargs = kwargs
            clock["t"] += 6.0                              # 첫 생성 6초 → 남은 3초 < 3.5초
            return GenerationResult(text="Bad.", finish_reason="stop")

    gen = Slow()
    monkeypatch.setattr(main_module, "generator", gen)
    r = client.post("/v1/light/sentence", json={"stat": "delta_app", "value": 0.08, "zone": "높음"})
    assert r.json()["fallback"] is True and gen.calls == 1
    assert gen.kwargs["lock_timeout"] == pytest.approx(9 - 3.5)


def test_차례가_오지_않으면_대체(monkeypatch):
    from app.generator import GeneratorBusyError

    class Busy:
        name = "busy"

        def generate_text(self, label, messages, decoding, **kwargs):
            raise GeneratorBusyError("다른 요청 생성 중")

    monkeypatch.setattr(main_module, "generator", Busy())
    items = [{"stat": "delta_app", "value": 0.08, "zone": "높음"}]
    r = client.post("/v1/light/summary", json={"items": items})
    assert r.status_code == 200 and r.json()["fallback"] is True


# ---------------- v5.1 (10/20 25번 측정 반영) ----------------
def test_v51_꼬리표와_플레이어_주어_정리():
    items = [("delta_app", 0.01, "보통"), ("delta_plonk", -0.05, "보통"), ("delta_vs_apm", 0.04, "보통")]
    t = ("블록당 공격량(APP): 최근 대전 상대들보다 조금 앞서지만 차이는 크지 않습니다.\n"
         "Plonk 성향: 맞붙은 상대들에 비해 약간 옅지만 차이는 크지 않습니다.\n"
         "수비 비중(VS/APM): 매 경기 상대와 견줘 보면 약간 큰 편이지만 차이는 크지 않습니다.")
    s, why = lc.parse_summary(t, items, random.Random(0))
    assert why is None and s.startswith("블록당 공격량(APP)은 최근") and ":" not in s
    items = [("strength_split", -0.25, "보통"), ("session_vs_slope", -0.3, "낮음"), ("delta_cheese_index", 3.4, "보통")]
    t = ("이 플레이어는 강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 낮은 편입니다. "
         "라운드별 공방 능력(VS) 변화: 라운드가 진행될수록 공방 능력(VS)이 처집니다. "
         "가비지 처리 성향(Cheese Index): 가비지 처리 성향(Cheese Index)이 약간 짙지만 차이는 크지 않습니다.")
    s, why = lc.parse_summary(t, items, random.Random(0))
    assert why is None and "플레이어" not in s and ":" not in s
    assert any(p + " 가비지" in s for p in lc.COMPARE_PHRASES)          # 비교 기준을 붙임


def test_v51_비교_기준_보완():
    s, why = lc.parse_sentence("보정 공격 효율(Weighted APP)은 비슷한 수준입니다.", "delta_weighted_app", 0.0, "보통",
                               random.Random(0))
    assert why is None and any(s.startswith(p) for p in lc._ZERO_PHRASES)
    # 지표 이름으로 시작하지 않으면 붙이지 않는다 (뜻이 바뀔 수 있음)
    _, why = lc.parse_sentence("이번에는 보정 공격 효율(Weighted APP)이 비슷한 수준입니다.", "delta_weighted_app", 0.0, "보통")
    assert "비교 기준" in why


def test_v51_강한_약한_순서_반대():
    items = [("strength_split", -0.55, "낮음"), ("delta_comeback", -0.10, "낮음"), ("session_vs_slope", 2.1, "높음")]
    t = ("약한 상대를 만났을 때의 승률이 강한 상대를 만났을 때보다 크게 낮습니다. "
         "유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강합니다. 라운드가 진행될수록 공방 능력(VS)이 뚜렷하게 강해집니다.")
    assert "방향 반대" in lc.parse_summary(t, items)[1]
    assert "방향 반대" in lc.check_sentence("약한 상대를 만났을 때의 승률이 강한 상대를 만났을 때보다 크게 낮습니다.",
                                          "strength_split", -0.55, "낮음")


def test_v51_요약_문장마다_비교_기준():
    items = [("strength_split", -0.10, "높음"), ("delta_stride", -0.30, "낮음"), ("delta_app", -0.02, "보통")]
    t = ("강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 조금 낮지만 크게 떨어지지 않습니다. 이번 Stride 성향이 옅게 나타납니다. "
         "맞붙은 상대들에 비해 블록당 공격량(APP)에서 조금 밀리지만 차이는 크지 않습니다.")
    assert "비교 기준 누락" in lc.parse_summary(t, items)[1]


def test_v51_지표_이름_반복():
    why = lc.check_sentence("블록당 공격량(APP)이 대전 상대 평균 대비 블록당 공격량(APP)에서 앞섭니다.", "delta_app", 0.08, "높음")
    assert why == "지표 이름 반복"


def test_v51_수비_비중_서술어_규칙():
    system = lc.build_sentence("delta_vs_apm", -0.2, "낮음", random.Random(0))[0]["content"]
    assert "'짙게', '옅게'는 쓰지 않습니다" in system


# ---------------- v5.2 (10/20 25번 v5.1 측정 반영) ----------------
def test_v52_소수점에서_문장을_자르지_않음():
    assert lc.clean_text("상대 대비 블록당 공격량(APP)에서 앞섭니다(+0.08). 둘째 문장입니다.", 1) == \
        "상대 대비 블록당 공격량(APP)에서 앞섭니다(+0.08)."
    assert "부호 숫자" in lc.parse_sentence("상대 대비 블록당 공격량(APP)에서 +0.08 높습니다.", "delta_app", 0.08, "높음")[1]


def test_v52_주어_반복_정리():
    s, why = lc.parse_sentence("블록당 공격량(APP)이 상대 대비 블록당 공격량(APP)에서 앞섭니다.", "delta_app", 0.08, "높음")
    assert why is None and s == "상대 대비 블록당 공격량(APP)에서 앞섭니다."


def test_v52_꼬리표_뒤_플레이어_주어():
    items = [("strength_split", 0.12, "높음"), ("delta_comeback", 0.12, "높음"), ("delta_opener", -0.05, "보통")]
    t = ("상대 강도별 승률 차: 이 레이어는 강한 상대를 만났을 때의 승률이 약한 상대를 만났을 때보다 오히려 높습니다. "
         "불리할 때 뒤집는 경향이 유리하다가 뒤집히는 경향보다 강합니다. 맞붙은 상대들에 비해 Opener 성향이 약간 옅지만 차이는 크지 않습니다.")
    s, why = lc.parse_summary(t, items, random.Random(0))
    assert why is None and s.startswith("강한 상대를")


@pytest.mark.parametrize("text,k,v,z,why", [
    ("매 경기 상대와 견해하면 블록당 공격량(APP)에서 조금 밀립니다만 차이는 크지 않습니다.", "delta_app", -0.02, "보통", "오타"),
    ("역전 경향의 값 -0.10은 유리하다가 뒤집히는 경향이 불리할 때 뒤집는 경향보다 강합니다.", "delta_comeback", -0.10, "낮음", "값 풀이"),
])
def test_v52_추가_검사(text, k, v, z, why):
    assert why in lc.check_sentence(text, k, v, z)


def test_v52_수비적_공격적_금지():
    why = lc.check_sentence("상대 대비 수비 비중(VS/APM)이 작고, 수비적인 편입니다.", "delta_vs_apm", -0.2, "낮음")
    assert "유형 규정" in why


def test_v52_범위_파일이_없어도_영역_판정(monkeypatch):
    def missing(*a, **k):
        raise FileNotFoundError("config/light_quartiles.json")
    monkeypatch.setattr(lc, "load_quartiles", missing)
    assert lc.resolve_zone("delta_comeback", 0.12, None) == "높음"
    assert lc.resolve_zone("strength_split", -0.10, None) == "높음"


def test_서비스_라이트는_기본_v2(monkeypatch):
    from fastapi import HTTPException

    monkeypatch.delenv("LLM_LIGHT_PROMPT", raising=False)

    def v2(req):
        raise HTTPException(status_code=418, detail="v2")

    monkeypatch.setattr(main_module, "_comment_light_v2", v2)
    import json
    from pathlib import Path
    body = json.loads((Path(__file__).parent / "samples" / "l6_typical.json").read_text(encoding="utf-8"))
    assert client.post("/v1/comment/light", json=body).status_code == 418
