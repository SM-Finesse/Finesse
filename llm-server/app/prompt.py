"""② 프롬프트 조립 (light, heavy).

근거: LLM/AI 파트 설계 v1.2
  - 7.1절 light 시스템 프롬프트 Baseline v2 (규칙 12개) — 문구를 그대로 옮김
  - 7.3절 heavy 공통 프롬프트 (초안) → heavy v3~v4 는 챕터별 프롬프트로 바꿈 (아래 heavy 이력)
  - 「코멘트 작성 가이드라인」(31번, 10/13·10/14) — heavy v4 의 비교 표현·서술어·금지 규칙
  - 7.2절 디코딩 파라미터
  - 5.5절 3번: chat template 형식(create_chat_completion)으로 학습·추론 포맷을 맞춤 (제안)

문구를 바꾸면 PROMPT_VERSION 도 올린다 (5.6절: 체크포인트에 사용한 프롬프트 버전 기록).
"""
import json
import random

from app.schemas import HeavyRequest, LightRequest

PROMPT_VERSION = "light-baseline-v2-trsum"  # v2 문구 + tr_trend 요약 입력 (10/7)

LIGHT_SYSTEM_PROMPT = """당신은 테트리스 게임 TETR.IO의 전적 데이터를 분석해 한국어로 코멘트를
작성하는 어시스턴트입니다. 입력으로 fixed_metrics와 delta_metrics(하이라이트
후보 최대 11개)가 주어집니다. delta 값은 같은 매치의 상대와 비교한 값입니다.

[해석 규칙]
1. delta_* 값이 양수면 상대보다 그 수치·성향이 강함(본인 우위)을 뜻합니다.
2. 플레이스타일 4개 값(delta_opener, delta_plonk, delta_stride, delta_inf_ds)은
   같은 실력대 기준으로 정규화한 성향 값의 상대와의 차이입니다. 서로 비교하거나
   절대값·백분위로 서술하지 말고, 상대 대비 강하다/약하다로만 서술하세요.
3. strength_split은 강한 상대 구간 승률에서 약한 상대 구간 승률을 뺀 값입니다.
   보통 음수이며, 음수 폭이 클수록 강한 상대에게 크게 무너진다는 뜻입니다.
   0에 가까우면 상대 강도와 상관없이 고르게 이기고, 양수면 강한 상대에게
   오히려 승률이 높습니다.
4. delta_comeback이 양수면 불리할 때 뒤집는 경향이 유리하다가 뒤집히는
   경향보다 강하다는 뜻입니다. 몇 판 차이로 뒤졌는지, 몇 판 중 몇 번인지는
   쓰지 말고 경향으로만 서술하세요.
5. session_vs_slope가 음수면 라운드가 진행될수록 처지는 경향, 양수면
   강해지는 경향이며, 0이면 뚜렷한 추세가 없다는 뜻입니다.

[선정·서술 규칙]
6. 하이라이트를 정확히 3개 선정하고 가장 중요한 순서대로 정렬하세요.
7. 값이 null이거나 입력에 없는 지표는 선정하지 말고 근거로도 쓰지 마세요.
8. 플레이어를 한 유형으로 규정하지 마세요. "이 지표에서 상대보다 이렇다"는
   식으로 좁혀서 서술하세요.
9. 입력에 없는 비율·백분율·수치를 만들어내지 마세요. 제공된 값만 인용하세요.
10. 추정 TR 등 입력에 없는 지표를 언급하지 마세요.

[출력 형식]
11. 다음 JSON으로만 응답하세요. 다른 문장은 쓰지 마세요.
    {"light_summary": "2-3문장 요약",
     "highlights": [{"stat": "지표 키", "sentence": "코멘트"}, ... 3개]}
12. stat에는 다음 키 중 하나를 그대로 쓰세요: delta_opener, delta_plonk,
    delta_stride, delta_inf_ds, delta_app, delta_weighted_app, delta_vs_apm,
    delta_cheese_index, strength_split, delta_comeback, session_vs_slope"""

# 디코딩 파라미터 (v1.2 7.2절)
LIGHT_DECODING = {
    "temperature": 0.4,
    "top_p": 0.9,
    "max_tokens": 377,
}


def summarize_tr_trend(values: list[float]) -> dict[str, float | int]:
    """tr_trend(시간순 TR 목록)를 짧은 요약으로 바꾼다.

    이유: 전적이 많은 유저는 tr_trend 가 80개 이상이라 프롬프트가 n_ctx(2048)를 넘는다
      (10/7 13번 서버 로그: Requested tokens (2668) exceed context window of 2048).
    요청 길이와 상관없이 항상 같은 형식으로 요약한다 → 파인튜닝 데이터도 이 형식으로 만든다.
    ※ 요약 방식은 v1.2 5.2절에 없음 — 설계 문서에 추가 필요.
    """
    if not values:
        return {"count": 0}
    first, last = values[0], values[-1]
    return {
        "count": len(values),
        "first": round(first, 2),
        "last": round(last, 2),
        "change": round(last - first, 2),
        "min": round(min(values), 2),
        "max": round(max(values), 2),
    }


def build_light_messages(req: LightRequest) -> list[dict[str, str]]:
    """chat template 형식의 메시지 목록을 만든다.

    user 메시지 = 요청 JSON을 압축 형태로 넣되, fixed_metrics.tr_trend 만 요약으로 바꾼다.
    exclude_unset: 백엔드가 보낸 형태를 그대로 유지한다
      (필드를 생략했으면 생략된 채로, null 로 보냈으면 null 로).
    학습 데이터에 두 형태를 모두 넣기로 했으므로(v1.2 5.1절) 바꾸지 않고 전달한다.
    """
    payload = req.model_dump(exclude_unset=True)
    payload["fixed_metrics"]["tr_trend"] = summarize_tr_trend(req.fixed_metrics.tr_trend)
    user_content = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return [
        {"role": "system", "content": LIGHT_SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]




# ---------------- heavy ----------------
HEAVY_PROMPT_VERSION = "heavy-v4.3-guide31r"
# 이력 (v1~v3.6 자세한 측정은 Drive 「헤비뷰 프롬프트 실험 결과 (v1~v3.6)」)
# v1 (10/7): v1.2 7.3절 공통 초안, 출력 JSON. 13번 통과 14/80.
# v2 (10/8): 출력을 "각주 문장만"으로 (JSON 은 서버가 조립). 40/80.
# v3 (10/8): 챕터별 프롬프트 — 그 챕터의 용어·해석·예시만, data 는 용어 이름으로. 25번 형식 71/80.
# v3.1 (10/12): 부호 해석을 서버가 — 값 옆에 방향을 붙임. playstyle 은 강한/약한 성향으로 묶음.
# v3.2 (10/12): 서버 내용 검사(입력에 없는 숫자, 방향 반대, 규칙 베낌, 라이벌 배지·숫자) → 502.
# v3.3 (10/12): 입력 방향과 같은 상황별 예시.
# v3.4 (10/12): 예시에서 숫자를 뺌, 정해진 횟수 실패 시 서버 대체 문장(app/fallback.py).
# v3.5 (10/12): rivals 는 서버 문장(LLM_HEAVY_TEMPLATE_CHAPTERS). strength_split "21%" → "21%p".
# v3.6 (10/12): playstyle 예시 이름 하나씩, [해석]에서 부호 규칙 문장 뺌. 11·13번 배포(llm-base-v1).
# v4 (10/16): 「코멘트 작성 가이드라인」(31번, 10/13 결정·10/14 보통 범위) 반영.
#     - 비교 기준 표현 5개(1.3절) 중 하나를 서버가 골라 "문장마다 한 번" 쓰게 함 (같은 말 반복 줄이기)
#     - 서술어를 지표 성격에 맞춤: 우열(attack) 앞섭니다/밀립니다, 성향(playstyle·defense) 짙게/옅게·큰/작은 편
#     - 방향이 같은 두 지표는 한 문장으로 묶는 예시 ("… 모두 큰 편입니다")
#     - %·%p 금지(D2) — strength_split 의 %p 변환을 없애고 값은 입력 그대로 괄호 인용
#     - tr_trend·session_vs_slope·strength_split 에는 "상대"(보다) 비교를 쓰지 않음 (1.2-7)
#     - strength_split 은 보통 범위(31번 2.2절 Q1~Q3)와 비교한 정도를 서버가 붙임
#     - rivals 는 승률을 보여 주지 않음(승률 계산 금지) — 경기 수·승·패만
#     ※ v1.2 7.3절(공통 초안)과 다름 — v1.3 개정 때 반영 필요.
# v4.1 (10/16): 31번 10/16 개정판 반영.
#     - 보통 범위(2.3절, train-2026-10-14) 안의 값은 "조금 … 차이는 크지 않습니다"로 과장하지 않게 (2.2·2.3절).
#       서버가 값 옆에 "차이 작음"을 붙이고, 범위 안 값에 "크게·뚜렷" 같은 말을 쓰면 검사에서 거절.
#     - "보통 수준", "드문 경우" 같은 전체 유저 분포 표현을 뺌 (1.3절 — 분포를 문장에 섞지 않음, 1.4절 9% 정정)
#     - 예시를 대체 문장과 같은 틀(app/fallback.py, 숫자 없는 판)로 만들어 방향·강도가 항상 입력과 같게
#     개인 PC(CUDA) 실측(10/16) 음수 샘플 54/80, 양수 샘플 63/80. attack·comeback·session·tr 은 10/10.
#     playstyle 2/10·1/10 — 입력 목록("상대보다 짙은 성향: Plonk(+0.18), Stride(+0.12, 차이 작음)")이나
#     [용어]의 "Opener: 초반 공격 위주 성향" 꼴을 그대로 옮겨 적음. strength_split 1/10·5/10 — "0.21% 낮습니다"(8건).
# v4.2 (10/16): playstyle 입력을 목록 대신 문장으로, 강도는 "차이 작음" 꼬리표 대신 묶음으로
#     (짙은 / 옅은 / 차이가 크지 않은). [용어]는 "이름: 뜻" 목록 대신 한 문장. 공통 규칙에 "목록·콜론 금지".
#     strength_split: 값과 같은 숫자에 붙은 "%"(0.21%, 21%)는 서버가 지운다 (v3.5 의 %p 고치기와 같은 방식,
#     D2 로 % 를 못 쓰므로 숫자째 뺌). [용어]의 "대부분 음수입니다"는 모델이 "음수이며"로 되풀이해 뺌.
#     개인 PC 실측(10/16) 음수 62/80, 양수 74/80 (rivals 는 서비스에서 서버 문장이라 빼면 60/70, 66/70).
#     통과 문장 중 흠: 괄호 꼬리표를 명사형 그대로 옮김("옅음입니다", "앞섬하며", "차이 작음."),
#     정의 되풀이("…을 뺀 값입니다"), "-62.87 낮습니다"(부호+낮다 이중 표현), TR 이름 지어냄("공격 점수(Tetra Rating)").
# v4.3 (10/16): 값 옆 괄호를 명사형 꼬리표 대신 그대로 쓸 수 있는 서술형 문장으로 ("상대보다 밀립니다").
#     검사 추가: 꼬리표 명사형, 정의 되풀이("뺀 값"), 부호 붙은 숫자 + 높다/낮다. TR 은 "TR(공식 대전 점수)"로만.

HEAVY_DECODING = {
    "temperature": 0.4,
    "top_p": 0.9,
    "max_tokens": 119,  # v1.2 7.2절
}

# 31번 1.3절 — 허용 비교 표현 5개. 새 표현을 만들지 않는다.
COMPARE_PHRASES = ("상대 대비", "맞붙은 상대들에 비해", "최근 대전 상대들보다", "대전 상대 평균 대비", "매 경기 상대와 견줘 보면")
# 상대와 비교한 값(delta_*)이 들어 있는 챕터 — 문장마다 비교 기준이 있어야 한다 (31번 1.3절, C1 (i))
DELTA_CHAPTERS = ("playstyle", "attack", "defense")
# 보통 범위 Q1~Q3 (31번 2.3절, calc 판정 상수 train-2026-10-14, train 498명). 범위 숫자는 문장에 쓰지 않는다.
# heavy 에서는 문장의 강도(크게/조금)를 정하는 데만 쓴다. light 처럼 판정해서 넘기는 값이 아니다.
HEAVY_RANGES_VERSION = "train-2026-10-14"
HEAVY_RANGES: dict[str, tuple[float, float]] = {
    "delta_opener": (-0.249, 0.171), "delta_plonk": (-0.122, 0.119),
    "delta_stride": (-0.162, 0.189), "delta_inf_ds": (-0.157, 0.255),
    "delta_app": (-0.043, 0.024), "delta_weighted_app": (-0.026, 0.026),
    "delta_vs_apm": (-0.100, 0.138), "delta_cheese_index": (-7.949, 13.852),
    "strength_split": (-0.395, -0.139), "session_vs_slope": (-0.080, 1.370),
}
STRENGTH_Q1, STRENGTH_Q3 = HEAVY_RANGES["strength_split"]

HEAVY_COMMON = """TETR.IO 전적 분석 리포트의 '{title}' 챕터 아래에 붙는 각주를 한국어로 씁니다.
아래 [용어]와 [해석]을 기준으로, 입력된 값이 무엇을 뜻하는지 1-2문장으로 짧게 설명하세요.

[규칙]
- 입력에 있는 값만 인용하세요. 값은 (+0.18)처럼 입력 그대로 괄호에 쓰고, %나 %p로 바꾸지 마세요.
- 입력에 없는 수치·판수·비율을 만들지 말고, 두 값의 차이도 계산하지 마세요.
- 영문 변수 이름(밑줄이 들어간 이름)은 쓰지 말고 [용어]의 이름을 쓰세요.
- 값 옆 괄호의 방향을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 용어의 정의를 다시 설명하지 말고, 값과 그 뜻만 쓰세요.
- 사람을 한 유형으로 규정하지 마세요. 문장의 주어는 사람이 아니라 지표로 쓰세요.
- 값 옆 괄호의 문장을 바탕으로 쓰세요. 괄호에 '크지 않습니다'가 있으면 '크게', '뚜렷하게'는 쓰지 마세요.
- 다른 유저와 비교하거나 '보통 수준', '드문 경우'처럼 쓰지 말고, 연승·연패·최근 몇 경기 같은 흐름도 쓰지 마세요.
- 추정 TR(Est. TR)처럼 입력에 없는 지표는 언급하지 마세요.
- 각주 문장만 쓰세요. 입력 줄을 목록으로 옮겨 적지 말고, 제목·번호·콜론(:)·따옴표·JSON도 쓰지 마세요."""

# 상대 비교 챕터에 덧붙이는 줄. {base} = 서버가 고른 비교 표현.
HEAVY_COMPARE_RULE = "- 비교 기준은 '{base}'를 문장마다 한 번 쓰세요. 비교 기준이 없는 문장은 쓰지 마세요."

# 챕터별 [용어]·[해석]·[예시]. 근거: 데이터명세서 2절, 05 공식 해설 3~8절, 04 지표 명세 6.2·8.1절, 31번 1.3·1.4·3.2절.
HEAVY_CHAPTERS: dict[str, dict[str, str]] = {
    "tr_trend": {
        "title": "TR · 능력치 추이",
        "terms": "- TR 추이: 최근 구간 평균 TR이 본인 전체 기간 평균 TR보다 얼마나 높거나 낮은지 (TR은 공식 대전 점수)",
        "rules": "- 상대와 비교한 값이 아니라 본인의 전체 평균과 비교한 값입니다. '상대'라는 말을 쓰지 마세요.\n"
                 "- 입력 괄호의 방향(높음·낮음)을 그대로 쓰세요. 단위는 TR 점수입니다.",
        "example": "최근 구간 TR이 전체 평균보다 높습니다. 최근 들어 점수가 오르는 흐름입니다.",
    },
    "playstyle": {
        "title": "플레이스타일 상대비교",
        "terms": "- Opener는 초반 공격, Plonk는 효율·가비지 활용, Stride는 속도, Inf DS는 다운스택(수비) 위주의 성향입니다.\n"
                 "- 각 값은 같은 실력대 기준 성향 값에서 상대 값을 뺀 차이입니다. 잘하고 못함이 아니라 성향입니다.",
        "rules": "- 입력의 짙은 성향, 옅은 성향, 차이가 크지 않은 성향을 1-2문장으로 쓰세요.\n"
                 "- 서술어는 '짙게 나타납니다', '옅게 나타납니다', '강한 편입니다', '약한 편입니다'를 쓰세요. "
                 "'앞섭니다', '밀립니다'는 쓰지 마세요.\n"
                 "- 네 성향끼리 비교하거나 순위를 매기지 말고, 절대값·백분위로 쓰지 마세요.",
        "example": "상대 대비 Inf DS 성향이 짙게 나타납니다. 반면 Stride 성향은 상대보다 옅은 편입니다.",
    },
    "attack": {
        "title": "공격 효율",
        "terms": "- 블록당 공격량(APP): 블록 1개당 공격량. 적은 블록으로 많은 공격을 할수록 높습니다.\n"
                 "- Weighted APP: Cheese Index(가비지 처리 성향)로 보정한 공격 효율입니다.\n"
                 "- 두 값 모두 본인 값에서 같은 매치 상대 값을 뺀 차이입니다.",
        "rules": "- 두 값의 방향은 입력 괄호에 있습니다. 서술어는 '앞섭니다', '밀립니다'를 쓰세요.\n"
                 "- 두 값의 방향이 같으면 한 문장으로 묶고, 다르면 문장을 나눠 각각 쓰세요.",
        "example": "상대 대비 블록당 공격량(APP)과 Weighted APP 모두에서 앞섭니다.",
    },
    "defense": {
        "title": "수비 · 가비지 처리",
        "terms": "- 수비 비중(VS/APM): 공격(APM) 대비 총 처리량(VS) 비율. 공격보다 방어에 치중할수록 큽니다.\n"
                 "- 가비지 처리 성향(Cheese Index): 공격 효율은 낮고 가비지 처리는 많은 판일수록 큽니다.\n"
                 "- 두 값 모두 본인 값에서 같은 매치 상대 값을 뺀 차이입니다. 잘하고 못함이 아니라 성향입니다.",
        "rules": "- 두 값의 방향은 입력 괄호에 있습니다. 서술어는 '큰 편입니다', '작은 편입니다', "
                 "'짙게 나타납니다', '옅게 나타납니다'를 쓰세요. '앞섭니다', '밀립니다'는 쓰지 마세요.\n"
                 "- 두 값의 방향이 같으면 한 문장으로 묶고, 다르면 문장을 나눠 각각 쓰세요.",
        "example": "상대 대비 수비 비중(VS/APM)과 가비지 처리 성향(Cheese Index)이 모두 큰 편입니다.",
    },
    "strength_split": {
        "title": "상대 강도별 승률",
        "terms": "- 상대 강도별 승률 차: 본인 기록 안에서 가장 강한 상대 구간 승률에서 "
                 "가장 약한 상대 구간 승률을 뺀 값(0~1 비율 차)",
        "rules": "- '상대보다', '상대 대비'라는 말을 쓰지 마세요. 값은 %로 바꾸지 말고 (-0.21)처럼 괄호에 쓰세요.\n"
                 "- 입력 괄호의 방향과 정도(크게 낮음·낮음·조금 낮음)를 그대로 쓰세요.\n"
                 "- 강한 상대 구간을 주어로 쓰세요. 구간을 나누는 방법 같은 정의는 설명하지 마세요.",
        "example": "강한 상대 구간의 승률이 약한 상대 구간보다 낮습니다. 강한 상대를 만나면 승률이 떨어지는 편입니다.",
    },
    "comeback_rate": {
        "title": "역전승 퍼포먼스",
        "terms": "- 역전승률: 경기 중 점수가 뒤졌던 매치(역전 기회) 중 이긴 비율\n"
                 "- 역전 허용률: 경기 중 점수가 앞섰던 매치(역전 허용 기회) 중 진 비율\n"
                 "- 기회·횟수가 함께 주어지면 \"몇 번 중 몇 번\"으로 그대로 인용할 수 있습니다.",
        "rules": "- 입력의 비교 줄(높음·낮음)을 그대로 쓰세요. 두 비율의 차이를 계산해 쓰지 마세요.\n"
                 "- 몇 판·몇 점 차이로 뒤졌는지는 쓰지 마세요(경기 형식마다 기준이 다릅니다).",
        "example": "역전승률이 역전 허용률보다 높습니다. 불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다.",
    },
    "session_vs_slope": {
        "title": "경기 내 컨디션 변화",
        "terms": "- 라운드별 VS 기울기: 한 매치 안에서 라운드가 진행될수록 평균 VS(공격과 수비를 합친 총 처리량)가 "
                 "라운드당 얼마나 오르거나 내리는지",
        "rules": "- 입력 괄호의 방향과 정도(크게 오름·조금 오름·내림)를 그대로 쓰세요. 상대와 비교한 값이 아니므로 '상대'라는 말을 쓰지 마세요.\n"
                 "- 값이 0이면 '뚜렷한 추세가 없습니다'로 쓰세요.",
        "example": "라운드가 진행될수록 VS가 조금 오르지만, 변화는 크지 않습니다.",
    },
    "rivals": {
        "title": "자주 만난 상대",
        "terms": "- 5경기 이상 만난 상대마다 마스킹된 닉네임, 경기 수, 승, 패가 주어집니다.\n"
                 "- 배지는 내 기준입니다. 천적 = 내가 그 상대에게 자주 짐, "
                 "우세 = 내가 그 상대에게 자주 이김, 접전 = 승패가 비슷함.\n"
                 "- 괄호에 배지가 없는 상대는 천적·우세·접전 어느 것도 아닙니다.",
        "rules": "- 닉네임은 마스킹된 그대로 쓰고, *를 복원하거나 추측하지 마세요.\n"
                 "- 천적·우세·접전은 입력 괄호에 그 말이 있는 상대에게만 쓰세요.\n"
                 "- 경기 수·승·패만 인용하고, 승률을 계산해 쓰지 마세요.\n"
                 "- 상대의 특징이나 전략은 지어내지 말고 '내가 그 상대에게 어떤지'로 쓰세요.",
        "example": "5경기 이상 만난 상대 중 한 명에게는 내가 자주 져서 천적입니다. 다른 상대와는 접전입니다.",
    },
}

# data 키 → 각주 입력에 보여 줄 용어 이름 (영문 키를 모델에게 보여 주지 않는다)
HEAVY_LABELS = {
    "tr_trend_delta": "TR 추이(최근 구간 평균 TR − 전체 평균 TR)",
    "delta_opener": "Opener 성향 차이",
    "delta_plonk": "Plonk 성향 차이",
    "delta_stride": "Stride 성향 차이",
    "delta_inf_ds": "Inf DS 성향 차이",
    "delta_app": "블록당 공격량(APP) 차이",
    "delta_weighted_app": "Weighted APP 차이",
    "delta_vs_apm": "수비 비중(VS/APM) 차이",
    "delta_cheese_index": "가비지 처리 성향(Cheese Index) 차이",
    "strength_split": "상대 강도별 승률 차(강한 상대 구간 − 약한 상대 구간)",
    "comeback_rate": "역전승률",
    "comeback_rate_against": "역전 허용률",
    "comeback_opportunities": "역전 기회(뒤졌던 매치 수)",
    "comeback_won": "역전승 횟수",
    "comeback_against_opportunities": "역전 허용 기회(앞섰던 매치 수)",
    "comeback_against_allowed": "역전 허용 횟수",
    "session_vs_slope": "라운드별 VS 기울기",
}

# 값 옆에 붙일 방향·강도. 부호 해석은 서버가 한다 (v3.1). 서술어는 31번 1.3절 표, 강도는 2.2·2.3절.
# 영역: high(Q3 위) / low(Q1 아래) / mid+·mid-(범위 안, 부호별) / zero. 범위가 없는 키는 high·low 만 쓴다.
HEAVY_DIRECTIONS: dict[str, dict[str, str]] = {
    # v4.3: 명사형 꼬리표("앞섬", "옅음, 차이 작음")를 모델이 그대로 옮겨 "옅음입니다"처럼 써서(10/16) 서술형으로
    "tr_trend_delta": {"high": "최근 구간 TR이 전체 평균보다 높습니다", "low": "최근 구간 TR이 전체 평균보다 낮습니다",
                       "zero": "최근 구간 TR이 전체 평균과 같습니다"},
    "delta_app": {"high": "상대보다 앞섭니다", "low": "상대보다 밀립니다",
                  "mid+": "상대보다 조금 앞서지만 차이는 크지 않습니다",
                  "mid-": "상대보다 조금 밀리지만 차이는 크지 않습니다", "zero": "상대와 같습니다"},
    "delta_weighted_app": {"high": "상대보다 앞섭니다", "low": "상대보다 밀립니다",
                           "mid+": "상대보다 조금 앞서지만 차이는 크지 않습니다",
                           "mid-": "상대보다 조금 밀리지만 차이는 크지 않습니다", "zero": "상대와 같습니다"},
    "delta_vs_apm": {"high": "상대보다 수비 비중이 큰 편입니다", "low": "상대보다 수비 비중이 작은 편입니다",
                     "mid+": "상대보다 수비 비중이 약간 큰 편이지만 차이는 크지 않습니다",
                     "mid-": "상대보다 수비 비중이 약간 작은 편이지만 차이는 크지 않습니다", "zero": "상대와 같습니다"},
    "delta_cheese_index": {"high": "상대보다 가비지 처리 성향이 짙게 나타납니다",
                           "low": "상대보다 가비지 처리 성향이 옅게 나타납니다",
                           "mid+": "상대보다 가비지 처리 성향이 약간 짙지만 차이는 크지 않습니다",
                           "mid-": "상대보다 가비지 처리 성향이 약간 옅지만 차이는 크지 않습니다",
                           "zero": "상대와 같습니다"},
    "session_vs_slope": {"high": "라운드가 진행될수록 VS가 크게 오릅니다", "low": "라운드가 진행될수록 VS가 내립니다",
                         "mid+": "라운드가 진행될수록 VS가 조금 오르지만 변화는 크지 않습니다",
                         "mid-": "라운드가 진행될수록 VS가 조금 내리지만 변화는 크지 않습니다",
                         "zero": "뚜렷한 추세가 없습니다"},
}
PLAYSTYLE_NAMES = {"delta_opener": "Opener", "delta_plonk": "Plonk", "delta_stride": "Stride", "delta_inf_ds": "Inf DS"}
# 04 지표 명세 8.1절
PLAYSTYLE_MEANINGS = {"Opener": "초반 공격", "Plonk": "효율·가비지 활용", "Stride": "속도", "Inf DS": "다운스택"}


def _num(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _sgn(value: object) -> str:
    """'+', '-', '0', 값이 없으면 ''."""
    if not _num(value):
        return ""
    return "+" if value > 0 else "-" if value < 0 else "0"


def heavy_zone(key: str, value: float) -> str:
    """보통 범위와 비교한 영역: 'high' / 'low' / 'mid' / 'zero'. 범위가 없는 키는 부호만 본다 (high·low·zero)."""
    if value == 0:
        return "zero"
    q = HEAVY_RANGES.get(key)
    if q is None:
        return "high" if value > 0 else "low"
    if value > q[1]:
        return "high"
    if value < q[0]:
        return "low"
    return "mid"


def strength_level(value: float) -> str:
    """strength_split 을 보통 범위(31번 2.2절 Q1~Q3)와 비교한 정도.
    'pos' 양수(드묾) / 'zero' / 'low' 범위 아래(강한 상대에게 크게 무너짐) /
    'mid' 범위 안 / 'high' 범위 위의 음수(차이가 작음, 잘 버팀).
    ※ 31번 2.5절 미결(영역과 부호가 반대일 때, 「claude/38」 T3): −0.10 은 영역으로는 '높음'이지만
      "강한 상대에게 더 강하다"가 아니라 "떨어지지만 폭이 작다"로 쓴다."""
    if value > 0:
        return "pos"
    if value == 0:
        return "zero"
    if value < STRENGTH_Q1:
        return "low"
    if value <= STRENGTH_Q3:
        return "mid"
    return "high"


STRENGTH_LABELS = {
    "pos": "강한 상대 구간 승률이 오히려 더 높습니다",
    "zero": "두 구간의 승률 차이가 없습니다",
    "low": "강한 상대 구간 승률이 크게 낮습니다",
    "mid": "강한 상대 구간 승률이 더 낮습니다",
    "high": "강한 상대 구간 승률이 조금 낮지만 차이는 크지 않습니다",
}


def pick_heavy_example(chapter_id: str, data: dict, base: str = COMPARE_PHRASES[0]) -> str:
    """입력 값의 방향·강도와 같은 상황의 예시 (v3.3~). v4.1 부터 대체 문장과 같은 틀에서 숫자만 뺀 문장.
    base = 상대 비교 챕터에서 쓸 비교 표현."""
    # fallback 이 prompt 를 import 하므로 함수 안에서 불러온다
    from app.fallback import build_heavy_text
    ex = build_heavy_text(chapter_id, data, random.Random(0), base=base, show=False)
    return ex or HEAVY_CHAPTERS[chapter_id]["example"].replace(COMPARE_PHRASES[0], base, 1)


def _signed(value: float) -> str:
    return f"{value:+g}"


def _direction(key: str, value: object) -> str | None:
    if not _num(value):
        return None
    if key == "strength_split":
        return STRENGTH_LABELS[strength_level(value)]
    if key not in HEAVY_DIRECTIONS:
        return None
    z = heavy_zone(key, value)
    if z == "mid":
        z = "mid+" if value > 0 else "mid-"
    return HEAVY_DIRECTIONS[key][z]


def _playstyle_lines(data: dict) -> list[str]:
    """v4.2: 목록을 그대로 옮겨 적는 일이 많아(10/16) 한 줄에 한 문장으로 준다. 강도는 묶음으로 나눈다."""
    groups: dict[str, list[str]] = {"짙은": [], "옅은": [], "작은": [], "같은": []}
    for key, name in PLAYSTYLE_NAMES.items():
        v = data.get(key)
        if not _num(v):
            continue
        z = heavy_zone(key, v)
        g = "같은" if v == 0 else "작은" if z == "mid" else "짙은" if v > 0 else "옅은"
        groups[g].append(f"{name}({_signed(v)})")
    tails = {"짙은": "상대보다 짙게 나타납니다.", "옅은": "상대보다 옅게 나타납니다.",
             "작은": "상대와 차이가 크지 않습니다.", "같은": "상대와 같습니다."}
    return [f"- {', '.join(items)} 성향은 {tails[g]}" for g, items in groups.items() if items]


def _comparison_line(data: dict) -> str | None:
    a, b = data.get("comeback_rate"), data.get("comeback_rate_against")
    if not (_num(a) and _num(b)):
        return None
    word = "높음" if a > b else "낮음" if a < b else "같음"
    return f"- 비교: 역전승률이 역전 허용률보다 {word}"


def _rival_badge(rate: float) -> str:
    """calc 05 공식 해설 7.3절 배지 기준 (서버가 계산 — v1.2 7.4절 배지 입력 여부 미결)."""
    if rate <= 0.40:
        return "천적"
    if rate >= 0.65:
        return "우세"
    if 0.45 <= rate <= 0.55:
        return "접전"
    return "-"


def _heavy_user_content(req: HeavyRequest) -> str:
    """data 를 용어 이름으로 바꾼 입력. 값이 없는(생략된) 지표는 줄을 만들지 않는다."""
    title = HEAVY_CHAPTERS[req.chapter_id]["title"]
    lines = [f"챕터: {title}"]
    if req.chapter_id == "rivals":
        for r in req.data.get("rivals") or []:
            matches, wins = r.get("matches") or 0, r.get("wins") or 0
            losses = r.get("losses") or 0
            badge = _rival_badge(wins / matches if matches else 0.0)
            # 승률은 보여 주지 않는다 (31번 3.2절 승률 계산 금지). 배지가 없으면 괄호 생략 (v3.1)
            lines.append(f"- {r.get('nickname_masked')}: {matches}경기 {wins}승 {losses}패"
                         + (f" ({badge})" if badge != "-" else ""))
    elif req.chapter_id == "playstyle":
        lines += _playstyle_lines(req.data)
    else:
        for key, value in req.data.items():
            if value is None:
                continue
            direction = _direction(key, value)
            shown = f"{_signed(value)} ({direction})" if direction else f"{value}"
            lines.append(f"- {HEAVY_LABELS.get(key, key)}: {shown}")
        if req.chapter_id == "comeback_rate" and (cmp := _comparison_line(req.data)):
            lines.append(cmp)
    if len(lines) == 1:
        lines.append("- (값 없음)")
    return "\n".join(lines)


def build_heavy_messages(req: HeavyRequest, base: str | None = None,
                         rng: random.Random | None = None) -> list[dict[str, str]]:
    """heavy 챕터 1건의 메시지.

    system = 공통 규칙 + 이 챕터의 [용어]·[해석]·[예시]만 (다른 챕터 규칙을 섞지 않는다)
    user   = data 를 용어 이름으로 바꾼 목록
    base   = 상대 비교 챕터의 비교 표현. 없으면 31번 1.3절 5개 중 하나를 고른다 (같은 말 반복 줄이기)
    """
    ch = HEAVY_CHAPTERS[req.chapter_id]
    if base is None:
        base = (rng or random).choice(COMPARE_PHRASES)
    rules = ch["rules"]
    if req.chapter_id in DELTA_CHAPTERS:
        rules += "\n" + HEAVY_COMPARE_RULE.format(base=base)
    system = (
        HEAVY_COMMON.format(title=ch["title"])
        + f"\n\n[용어]\n{ch['terms']}\n\n[해석]\n{rules}\n\n[예시] (값은 실제 입력과 다름)\n"
        + pick_heavy_example(req.chapter_id, req.data, base)
    )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": _heavy_user_content(req)},
    ]
