"""「P1 프롬프트에 넣을 규칙 — 최종(최소안)」(10/26, 정본 claude/31) — light-v5.6 · heavy-v4.5 검사."""
import json
import random

import pytest

from app import light_comment as lc
from app.fallback import build_fallback
from app.names import COMMENT_NAMES, normalize_names
from app.prompt import fmt_num
from app.validation import OutputFormatError, process_heavy_output

with open("tests/samples/heavy_chapters.json", encoding="utf-8") as f:
    CHAPTERS = json.load(f)


def test_이름_사전():
    assert COMMENT_NAMES["delta_inf_ds"] == "Infinite Downstack 성향"
    assert normalize_names("Infinite Downstacking 성향이 짙습니다.") == "Infinite Downstack 성향이 짙습니다."


@pytest.mark.parametrize("text, k, v, z", [   # 7절 few-shot 예시 (개별)
    ("맞붙은 상대들에 비해 Infinite Downstack 성향이 눈에 띄게 짙습니다(+0.31).", "delta_inf_ds", 0.31, "높음"),
    ("강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편입니다(−0.26).", "strength_split", -0.26, "높음"),
    ("라운드가 진행될수록 공방 능력이 오르는 흐름입니다(+1.01).", "session_vs_slope", 1.01, "보통"),
])
def test_7절_예시_개별_통과(text, k, v, z):
    assert lc.parse_sentence(text, k, v, z)[1] is None


@pytest.mark.parametrize("text, items", [   # 7절 few-shot 예시 (요약)
    (("이번 분석에서 눈에 띄는 점은 맞붙은 상대들에 비해 Infinite Downstack 성향이 뚜렷하게 짙다는 것입니다. "
      "수비 비중도 상대보다 확연히 크고, 보정 공격 효율에서도 상대보다 분명하게 앞섭니다."),
     [("delta_inf_ds", 0.31, "높음"), ("delta_vs_apm", 0.15, "높음"), ("delta_weighted_app", 0.08, "높음")]),
    (("강한 상대 구간에서도 약한 상대 구간과 비교해 승률이 크게 흔들리지 않는 편입니다. 맞붙은 상대들과의 경기에서는 "
      "역전패 경향이 역전승 경향보다 뚜렷하게 강하고, 라운드가 진행될수록 공방 능력이 오르는 흐름입니다."),
     [("strength_split", -0.26, "높음"), ("delta_comeback", -0.03, "낮음"), ("session_vs_slope", 1.01, "보통")]),
])
def test_7절_예시_요약_통과(text, items):
    assert lc.parse_summary(text, items)[1] is None


@pytest.mark.parametrize("text, k, v, z, why", [
    ("상대 대비 블록당 공격력이 뚜렷하게 높습니다(+0.08).", "delta_app", 0.08, "높음", "높다·강하다"),
    ("상대 대비 Opener 성향이 뚜렷하게 강합니다(+0.42).", "delta_opener", 0.42, "높음", "크기·세기"),
    ("상대 대비 가비지 처리 성향이 큰 편입니다(+2.6).", "delta_cheese_index", 2.56, "보통", "크기로"),
    ("상대 대비 Opener 성향이 뚜렷하게 짙습니다(+0.4).", "delta_opener", 0.42, "높음", "다시 반올림"),
    ("상대 대비 Opener 성향이 +0.42만큼 뚜렷하게 짙습니다.", "delta_opener", 0.42, "높음", "괄호 밖"),
    ("상대 대비 Opener 성향이 뚜렷하게 짙어 초반 공격을 더 해야 합니다(+0.42).", "delta_opener", 0.42, "높음", "처방"),
    ("상대 대비 Opener 성향이 드물게 뚜렷하게 짙습니다(+0.42).", "delta_opener", 0.42, "높음", "다른 유저"),
    ("상대 대비 Opener 성향이 뚜렷하게 짙고 수비 비중도 큽니다(+0.42).", "delta_opener", 0.42, "높음", "입력에 없는 지표"),
])
def test_개별_거부(text, k, v, z, why):
    got = lc.parse_sentence(text, k, v, z)[1]
    assert got is not None and why in got, got


def test_괄호_값_부호_붙임():
    s, why = lc.parse_sentence("상대 대비 Opener 성향이 뚜렷하게 짙습니다(0.42).", "delta_opener", 0.42, "높음")
    assert why is None and s.endswith("(+0.42).")


def test_역전_원값():
    assert lc.fmt_value("delta_comeback", -0.025) == "-0.025"
    assert lc.fmt_value("delta_cheese_index", -2.56) == "-2.6"


ITEMS = [("delta_inf_ds", 0.31, "높음"), ("delta_vs_apm", 0.15, "높음"), ("delta_weighted_app", 0.08, "높음")]


@pytest.mark.parametrize("text, why", [
    (("맞붙은 상대들에 비해 Infinite Downstack 성향이 뚜렷하게 짙습니다. 반면 상대 대비 수비 비중이 큰 편이고, "
      "상대보다 보정 공격 효율에서 앞섭니다."), "연결어"),
    (("맞붙은 상대들에 비해 Infinite Downstack 성향이 짙고, 상대 대비 수비 비중이 크며, 상대보다 보정 공격 효율에서 앞서 "
      "전반적으로 잘합니다."), "종합 평가"),
    ("상대 대비 Infinite Downstack 성향과 수비 비중이 뚜렷하게 크고, 상대보다 보정 공격 효율에서 앞섭니다.", "한 주어"),
])
def test_요약_거부(text, why):
    got = lc.parse_summary(text, ITEMS)[1]
    assert got is not None and why in got, got


def test_요약_괄호_값은_뗌():
    s, why = lc.parse_summary("맞붙은 상대들에 비해 Infinite Downstack 성향이 뚜렷하게 짙습니다(+0.31). 상대 대비 수비 비중이 뚜렷하게 "
                              "크고, 상대보다 보정 공격 효율에서 뚜렷하게 앞섭니다(+0.08).", ITEMS)
    assert why is None and "0." not in s


@pytest.mark.parametrize("seed", range(10))
def test_대체_요약은_이어_붙이기가_아님(seed):
    t = lc.build_summary_text(ITEMS, random.Random(seed))
    assert t.startswith(lc._SUMMARY_OPENERS) and "것입니다." in t and "(" not in t
    assert lc.parse_summary(t, ITEMS)[1] is None


# ---------------- heavy ----------------
def test_heavy_입력_값_자리수():
    assert fmt_num("delta_cheese_index", -2.56) == "-2.6"
    assert fmt_num("tr_trend_delta", -62.871) == "-62.87"
    assert fmt_num("comeback_opportunities", 12, signed=False) == "12"


@pytest.mark.parametrize("ch, text, why", [
    ("playstyle", "대전 상대 평균 대비 Plonk 성향이 약한 편입니다(-0.22).", "짙다·옅다"),
    ("playstyle", "상대 대비 Plonk 성향이 특히 옅게 나타납니다(-0.22).", "C2"),
    ("attack", "상대 대비 블록당 공격력이 낮습니다(-0.05).", "높다·강하다"),
    ("defense", "상대 대비 수비 비중과 가비지 처리 성향이 모두 작은 편입니다.", "한 주어"),
    ("defense", "상대 대비 가비지 처리 성향이 작은 편입니다(-2.6).", "크다·작다"),
    ("strength_split", "강한 상대 구간에서는 약한 상대 구간보다 승률이 떨어지니 강한 상대와 더 연습해야 합니다.", "처방"),
])
def test_heavy_거부(ch, text, why):
    with pytest.raises(OutputFormatError, match=why):
        process_heavy_output(text, ch, CHAPTERS[ch])


@pytest.mark.parametrize("ch, text", [
    ("tr_trend", "최근 구간 TR이 전체 평균 TR보다 낮습니다(-62.87)."),
    ("strength_split", "강한 상대 구간에서는 약한 상대 구간보다 승률이 떨어집니다(-0.21)."),
    ("comeback_rate", "역전 기회 12번 중 3번을 뒤집었고, 앞서던 경기 10번 중 4번을 내줬습니다."),
    ("session_vs_slope", "라운드가 진행될수록 공방 능력이 조금 오르지만, 변화는 크지 않습니다(+0.55)."),
])
def test_heavy_규칙_틀_통과(ch, text):
    assert process_heavy_output(text, ch, CHAPTERS[ch]).footnote == text


def test_heavy_역전_대체_문장은_표본_틀():
    t = build_fallback("comeback_rate", CHAPTERS["comeback_rate"], random.Random(0))
    assert t.startswith("역전 기회 12번 중 3번을 뒤집었고, 앞서던 경기 10번 중 4번을 내줬습니다.")


def test_heavy_다시_반올림한_숫자는_뗌():
    assert process_heavy_output("최근 구간 TR이 전체 평균 TR보다 약 63점 낮습니다.", "tr_trend",
                                CHAPTERS["tr_trend"]).footnote == "최근 구간 TR이 전체 평균 TR보다 낮습니다."


SUM3 = [("delta_app", 0.08, "높음"), ("delta_opener", 0.42, "높음"), ("delta_vs_apm", -0.2, "낮음")]
ZERO3 = [("session_vs_slope", 0.0, "보통"), ("delta_inf_ds", 0.4, "높음"), ("delta_weighted_app", 0.0, "보통")]


@pytest.mark.parametrize("text, items, fixed", [   # 10/27 25번 실측 — 요약 틀을 기계적으로 맞춤 (뜻은 그대로)
    (("이번 분석에서 눈에 띄는 점은 라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다. 상대 대비 Infinite Downstack 성향이 "
      "뚜렷하게 짙고, 맞붙은 상대들에 비해 보정 공격 효율은 같은 수준입니다."), ZERO3,
     "라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다."),
    (("상대 대비 블록당 공격력에서 뚜렷하게 앞선다는 것을 눈에 띄게 합니다. 맞붙은 상대들에 비해 Opener 성향이 뚜렷하게 짙고, "
      "상대 대비 수비 비중이 뚜렷하게 작은 편입니다."), SUM3,
     "이번 분석에서 눈에 띄는 점은 상대 대비 블록당 공격력에서 뚜렷하게 앞선다는 것입니다."),
    (("상대 대비 블록당 공격력에서 뚜렷하게 앞섭니다. 맞붙은 상대들에 비해 Opener 성향이 뚜렷하게 짙습니다. "
      "상대 대비 수비 비중이 뚜렷하게 작은 편입니다."), SUM3,
     "이번 분석에서 눈에 띄는 점은 상대 대비 블록당 공격력에서 뚜렷하게 앞선다는 것입니다. 맞붙은 상대들에 비해 Opener 성향이 뚜렷하게 짙고,"),
])
def test_요약_틀_맞춤(text, items, fixed):
    s, why = lc.parse_summary(text, items)
    assert why is None and s.startswith(fixed), (why, s)


MIX3 = [("delta_weighted_app", -0.05, "낮음"), ("delta_cheese_index", 25.3, "높음"), ("delta_comeback", 0.02, "보통")]
HOLD3 = [("strength_split", -0.1, "높음"), ("delta_stride", -0.3, "낮음"), ("delta_app", -0.02, "보통")]


def test_요약_입력_줄_꼴은_방향_문장만():   # 10/27 v5.6b 실측 — '이름: 값 …, 방향: 문장' 세 줄
    raw = ("보정 공격 효율: 값 -0.05, 방향: 보정 공격 효율에서 뚜렷하게 밀립니다\n"
           "가비지 처리 성향: 값 +25.3, 방향: 가비지 처리 성향이 뚜렷하게 짙습니다\n"
           "역전승·역전패 경향: 값 +0.02, 방향: 역전승 경향이 역전패 경향보다 강한 편입니다")
    s, why = lc.parse_summary(raw, MIX3)
    assert why is None, why
    assert s.startswith("이번 분석에서 눈에 띄는 점은 ") and "값" not in s and "방향" not in s
    assert s.endswith("역전승 경향이 역전패 경향보다 강한 편입니다.")


def test_요약_문장_끝_뒤_쉼표는_잇는_꼴로():   # 10/27 v5.6b 실측 — '… 옅습니다, …'
    raw = ("이번 분석에서 눈에 띄는 점은 강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편이라는 것입니다. "
           "Stride 성향이 뚜렷하게 옅습니다, 맞붙은 상대들에 비해 블록당 공격력에서 밀리는 편입니다.")
    s, why = lc.parse_summary(raw, HOLD3)
    assert why is None, why
    assert "옅습니다," not in s and "뚜렷하게 옅고, 맞붙은 상대들에 비해 블록당 공격력에서 밀리는 편입니다." in s


def test_가비지_처리_성향_높은_편_거부():   # P1 규칙 2절: 짙다·옅다(강하다·약하다)
    why = lc.check_sentence("대전 상대 평균 대비 가비지 처리 성향이 높은 편입니다.", "delta_cheese_index", 3.4, "보통")
    assert why and "가비지" in why


def test_소문자_infinite_downstack_이름_고침():
    assert normalize_names("infinite downstack 성향이") == "Infinite Downstack 성향이"


@pytest.mark.parametrize("text", [   # 10/27 v4.5b 실측 — 꼬리표·주어 반복은 떼고 통과
    "수비 비중: 상대보다 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다. 가비지 처리 성향: 상대보다 가비지 처리 성향이 약간 옅지만 차이는 크지 않습니다.",
    "수비 비중은 상대보다 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다. 가비지 처리 성향은 상대보다 가비지 처리 성향이 약간 옅지만 차이는 크지 없습니다.",
])
def test_heavy_수비_꼬리표_주어반복_뗌(text):
    out = process_heavy_output(text, "defense", CHAPTERS["defense"]).footnote
    assert out == ("상대보다 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다. "
                   "상대보다 가비지 처리 성향이 약간 옅지만 차이는 크지 않습니다.")


@pytest.mark.parametrize("text", [   # 10/27 v4.5b 실측 — 라운드 서술어는 오른다·처진다
    "라운드가 진행될수록 공방 능력이 힘이 빠집니다.",
    "라운드가 진행될수록 공방 능력이 작아지고, 경기 후반으로 갈수록 처리량이 줄어듭니다.",
])
def test_heavy_라운드_서술어_거부(text):
    with open("tests/samples/heavy_chapters_pos.json", encoding="utf-8") as f:
        d = json.load(f)["session_vs_slope"]
    with pytest.raises(OutputFormatError):
        process_heavy_output(text, "session_vs_slope", d)


@pytest.mark.parametrize("ch, text, fixed", [   # 10/27 v4.5c 실측 — 같은 방향의 다른 말·꼬리표·오타를 기계적으로 고침
    ("tr_trend", "TR 추이: 최근 구간 평균 TR이 전체 평균 TR보다 낮습니다.", "최근 구간 평균 TR이 전체 평균 TR보다 낮습니다."),
    ("attack", "블록당 공격력은 상대 대비 낮습니다. 보정 공격 효율은 상대 대비 낮습니다.",
     "블록당 공격력에서 상대보다 밀립니다. 보정 공격 효율에서 상대보다 밀립니다."),
    ("defense", "매 경기 상대와 견해하면 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다.",
     "매 경기 상대와 견줘 보면 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다."),
])
def test_heavy_기계적_손질_v45d(ch, text, fixed):
    assert process_heavy_output(text, ch, CHAPTERS[ch]).footnote == fixed


def test_heavy_라운드_줄어듭니다는_처집니다로():
    with open("tests/samples/heavy_chapters_pos.json", encoding="utf-8") as f:
        d = json.load(f)["session_vs_slope"]
    out = process_heavy_output("라운드가 진행될수록 공방 능력이 줄어듭니다.", "session_vs_slope", d).footnote
    assert out == "라운드가 진행될수록 공방 능력이 처집니다."


def test_heavy_플레이스타일_소문자_목록기호():
    with open("tests/samples/heavy_chapters_pos.json", encoding="utf-8") as f:
        d = json.load(f)["playstyle"]
    out = process_heavy_output("-opener(-0.05) 성향은 상대보다 옅게 나타납니다.", "playstyle", d).footnote
    assert out == "Opener(-0.05) 성향은 상대보다 옅게 나타납니다."


def test_heavy_플레이스타일_이름_겹침_거부():
    with pytest.raises(OutputFormatError):
        process_heavy_output("Opener(+0.10)의 Opener 성향은 상대보다 조금 짙지만 차이는 크지 않습니다.", "playstyle",
                             CHAPTERS["playstyle"])
