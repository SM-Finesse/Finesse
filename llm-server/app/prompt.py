"""② 프롬프트 조립 (light, heavy).

근거: LLM/AI 파트 설계 v1.2
  - 7.1절 light 시스템 프롬프트 Baseline v2 (규칙 12개) — 문구를 그대로 옮김
  - 7.3절 heavy 공통 프롬프트 (초안 — 팀 검토 전), 7.4절 라이벌 프롬프트 지침 — 문구를 그대로 옮김
  - 7.2절 디코딩 파라미터
  - 5.5절 3번: chat template 형식(create_chat_completion)으로 학습·추론 포맷을 맞춤 (제안)

문구를 바꾸면 PROMPT_VERSION 도 올린다 (5.6절: 체크포인트에 사용한 프롬프트 버전 기록).
"""
import json

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
HEAVY_PROMPT_VERSION = "heavy-v3.6-norules"
# 이력
# v1 (10/7): v1.2 7.3절 공통 초안 그대로, 출력 JSON. 13번 실측 8챕터×10회 통과 14/80.
# v2 (10/8): 출력을 "각주 문장만"으로 변경 (JSON 은 서버가 조립). 통과 40/80.
#     남은 실패 대부분이 지표 키 이름 노출, 통과 문장도 챕터와 무관한 "플레이스타일 … 무너진다".
# v3 (10/8): 챕터별 프롬프트 — 그 챕터의 용어·해석 규칙·예시 문장만 넣고, data 를 영문 키 대신 용어 이름으로 전달.
#     용어·부호 해석의 근거: 데이터명세서 2절, calc 05 공식 해설 3·4·5·6·7.3·8절, 04 지표 명세 6.2·8.1절.
#     ※ v1.2 7.3절(공통 프롬프트·JSON 출력)과 다름 — 문서 갱신·팀 검토 필요.
#     25번 실측(10/12) 형식 통과 71/80. 남은 문제는 내용: 부호를 거꾸로 읽음(attack 2문장째, defense Cheese,
#     comeback, strength_split), playstyle 은 입력 목록을 그대로 베껴 502(8/10), rivals 배지 "-" 를 오해석.
# v3.1 (10/12): 부호 해석을 서버가 한다 — 값 옆에 "(상대보다 낮음)" 같은 방향을 붙여 준다
#     (라이벌 승률·배지를 서버가 계산하는 것과 같은 원칙, v1.2 3절 "LLM 은 수치를 계산하지 않음").
#     playstyle 은 강한 성향/약한 성향 두 줄로 묶어 준다. rivals 는 배지가 없으면 괄호를 생략한다.
#     25번 실측(10/12) 형식 통과 75/80, 내용 맞음 약 57/80 (v3 약 42). 남은 문제: attack 둘째 문장 "함께 높아"
#     (예시 베낌), strength_split "0.21%", 예시 값(+0.13)·없는 승수 지어냄, rivals 배지 오용, [해석] 규칙 베낌.
# v3.2 (10/12): 서버 검사 추가 (validation.py) — 입력에 없는 숫자, "0.21%" 꼴, 규칙 문장 베낌,
#     지표 뒤 첫 방향 단어가 부호와 반대, 라이벌별 배지·숫자 불일치 → 502 (백엔드가 그 챕터만 재요청).
#     strength_split 은 %p 로도 보여 준다.
#     25번 실측(10/12) 통과 55/80, 통과한 문장 중 내용 맞음 51/55. attack 2/10·rivals 2/10 — 모델이 예시 문장을
#     그대로 따라 써서("함께 높아", "접전") 검사에 걸림.
# v3.3 (10/12): 상황별 예시 — 서버가 실제 값의 방향(부호·배지)을 보고 그 방향과 같은 예시를 골라 넣는다
#     (예시를 따라 써도 방향이 맞게). 숫자·닉네임은 실제와 다르게 두고, 베끼면 숫자·닉네임 검사에 걸린다.
#     25번 실측(10/12) 음수 샘플 61/80, 반대 방향 샘플 62/80. attack 2→10/10. 남은 문제: 예시 숫자·이름 베낌
#     (playstyle), 값 하나인 챕터의 방향 오류가 검사를 빠져나감, strength_split 정의 되풀이, rivals 배지 오해.
# v3.4 (10/12): 예시에서 숫자를 뺌. playstyle 예시는 실제 강한/약한 성향 이름으로 만든다. rivals 배지를
#     "내 기준"으로 명시. 검사 추가(값 하나인 챕터 방향, 규칙 되풀이, 라이벌 묘사). 그리고 LLM 이 정해진 횟수만큼
#     실패하면 서버가 대체 문장(app/fallback.py)을 보낸다 → 각주가 비지 않음.
#     25번 실측(10/12) 63/80, 통과 문장 거의 모두 내용 맞음. rivals 2/10 (배지 설명을 넣자 오히려 "내가 자주
#     이기는 우세"로 거꾸로 씀), strength_split 3/10 (그중 4개는 "21%" 를 %p 없이 씀).
# v3.5 (10/12): rivals 는 LLM 을 부르지 않고 서버 문장으로 (LLM_HEAVY_TEMPLATE_CHAPTERS, 기본 rivals).
#     strength_split 의 "21%" 는 값과 같으면 "21%p" 로 고쳐 준다.
#     25번 반대 방향 샘플(v3.4) 51/80: playstyle 1/10 — 예시 "Plonk·Stride 성향이 강하고"를 모델이
#     "Plonk 성향이 강하고 Stride 성향은 약합니다"로 잘못 나눠 씀. defense·strength 는 [해석]의 "양수면 …" 규칙을 베낌.
# v3.6 (10/12): playstyle 예시는 강한/약한 성향 이름을 하나씩만. [해석]에서 부호 규칙("양수면/음수면")을 뺌
#     (방향은 입력 괄호로 이미 주고 있음 — v3.1).

HEAVY_DECODING = {
    "temperature": 0.4,
    "top_p": 0.9,
    "max_tokens": 119,  # v1.2 7.2절
}

HEAVY_COMMON = """TETR.IO 전적 분석 리포트의 '{title}' 챕터 아래에 붙는 각주를 한국어로 씁니다.
아래 [용어]와 [해석]을 기준으로, 입력된 값이 무엇을 뜻하는지 1-2문장으로 설명하세요.

[규칙]
- 입력에 있는 값만 인용하고, 입력에 없는 수치·판수·비율은 만들지 마세요.
- 영문 변수 이름(밑줄이 들어간 이름)은 쓰지 말고 [용어]의 이름을 쓰세요.
- [용어]에 없는 지표는 언급하지 마세요.
- 값 옆 괄호의 방향(높음·낮음, 오름·내림)을 그대로 따르세요. 부호를 다시 해석하지 마세요.
- 용어의 정의를 다시 설명하지 말고, 값과 그 뜻만 쓰세요.
- 플레이어를 한 유형으로 규정하지 마세요. "이 지표에서 상대보다 이렇다"로 좁혀 쓰세요.
- 추정 TR(Est. TR)처럼 입력에 없는 지표는 언급하지 마세요.
- 각주 문장만 쓰세요. 제목, 목록, 따옴표, JSON은 쓰지 마세요."""

# 챕터별 [용어]·[해석]·[예시]. 예시 문장의 값은 일부러 입력과 다르게 둔다(값을 베끼지 않게).
HEAVY_CHAPTERS: dict[str, dict[str, str]] = {
    "tr_trend": {
        "title": "TR · 능력치 추이",
        "terms": "- TR 추이: 최근 몇 판의 평균 TR(Tetra Rating, 공식 대전 점수)에서 전체 기간 평균 TR을 뺀 값",
        "rules": "- 입력 괄호의 방향(상승세·하락세)을 그대로 쓰세요.\n"
                 "- 단위는 TR 점수입니다. 상대와 비교한 값이 아니라 본인의 과거와 비교한 값입니다.",
        "example": "최근 경기의 TR이 전체 기간 평균보다 120점 높습니다. 최근 들어 점수가 오르는 흐름입니다.",
    },
    "playstyle": {
        "title": "플레이스타일 상대비교",
        "terms": "- Opener: 초반 공격 위주 성향\n- Plonk: 효율·가비지 활용 위주 성향\n"
                 "- Stride: 속도 위주 성향\n- Inf DS: 다운스택(수비) 위주 성향\n"
                 "- 각 값은 같은 실력대 기준으로 정규화한 성향 값에서 상대 값을 뺀 차이입니다.\n"
                 "- 입력은 상대보다 강한 성향과 약한 성향으로 나뉘어 주어집니다.",
        "rules": "- 강한 성향과 약한 성향을 각각 한 문장으로, 목록이 아닌 문장으로 쓰세요.\n"
                 "- 네 성향끼리 크기를 비교하거나 순위를 매기지 말고, 절대값·백분위로 쓰지 마세요.",
        "example": "상대와 비교하면 Inf DS 성향이 강하고(+0.13) Stride 성향은 약합니다(-0.05). "
                   "상대보다 다운스택 쪽 성향이 뚜렷하다는 뜻입니다.",
    },
    "attack": {
        "title": "공격 효율",
        "terms": "- APP: 블록 1개당 공격량(APM ÷ (PPS × 60)). 적은 블록으로 많은 공격을 할수록 높습니다.\n"
                 "- Weighted APP: Cheese Index(가비지 처리 성향)로 보정한 공격 효율입니다.\n"
                 "- 두 값 모두 본인 값에서 같은 매치 상대 값을 뺀 차이입니다.",
        "rules": "- 두 값의 방향은 입력 괄호에 있습니다. 두 값의 방향을 각각 따로 쓰세요.",
        "example": "블록당 공격량(APP)이 상대보다 많습니다(+0.04). Weighted APP도 함께 높아 공격 효율 우위가 뚜렷합니다.",
    },
    "defense": {
        "title": "수비 · 가비지 처리",
        "terms": "- VS/APM: 공격(APM) 대비 총 처리량(VS) 비율. 공격보다 방어(가비지 처리)에 치중할수록 높습니다.\n"
                 "- Cheese Index: 공격 효율은 낮고 가비지 처리는 많은 판일수록 높은 가비지 처리 성향 지표입니다.\n"
                 "- 두 값 모두 본인 값에서 같은 매치 상대 값을 뺀 차이입니다.",
        "rules": "- 두 값의 방향은 입력 괄호에 있습니다. 두 값의 방향을 각각 따로 쓰세요.\n"
                 "- 플레이어가 공격형인지 수비형인지 규정하지 마세요.",
        "example": "공격 대비 수비 비율(VS/APM)이 상대보다 높습니다(+0.12). "
                   "Cheese Index도 높아 상대보다 가비지 처리 성향이 강합니다.",
    },
    "strength_split": {
        "title": "상대 강도별 승률",
        "terms": "- 상대 강도별 승률 차: 만난 상대를 TR 격차로 5등분해, 가장 강한 상대 구간 승률에서 "
                 "가장 약한 상대 구간 승률을 뺀 값(0~1 비율 차)",
        "rules": "- 입력 괄호의 방향(더 높음·더 낮음)을 그대로 쓰고, 차이는 괄호 안의 %p 값으로 쓰세요.\n"
                 "- 강한 상대 구간을 주어로 쓰세요.\n"
                 "- 구간을 나누는 방법 같은 지표의 정의는 설명하지 마세요.",
        "example": "강한 상대 구간 승률이 약한 상대 구간보다 35%p 낮습니다. 강한 상대를 만나면 승률이 크게 떨어지는 편입니다.",
    },
    "comeback_rate": {
        "title": "역전승 퍼포먼스",
        "terms": "- 역전승률: 경기 중 점수가 뒤졌던 매치(역전 기회) 중 이긴 비율\n"
                 "- 역전 허용률: 경기 중 점수가 앞섰던 매치(역전 허용 기회) 중 진 비율\n"
                 "- 기회·횟수가 함께 주어지면 \"몇 번 중 몇 번\"으로 인용할 수 있습니다.",
        "rules": "- 역전승률이 역전 허용률보다 높으면 불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다.\n"
                 "- 몇 판 차이로 뒤졌는지는 쓰지 마세요(경기 형식마다 기준이 다릅니다).",
        "example": "역전승률(0.30)이 역전 허용률(0.20)보다 높습니다. "
                   "불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다.",
    },
    "session_vs_slope": {
        "title": "경기 내 컨디션 변화",
        "terms": "- 라운드별 VS 기울기: 한 매치 안에서 라운드가 진행될수록 평균 VS(공격과 수비를 합친 총 처리량)가 "
                 "라운드당 얼마나 오르거나 내리는지",
        "rules": "- 입력 괄호의 방향(오름·내림)을 그대로 쓰세요.",
        "example": "라운드가 진행될수록 VS가 라운드당 약 3씩 오릅니다. 경기가 길어질수록 강해지는 흐름입니다.",
    },
    "rivals": {
        "title": "자주 만난 상대",
        "terms": "- 5경기 이상 만난 상대마다 마스킹된 닉네임, 경기 수, 승, 패, 승률이 주어집니다.\n"
                 "- 배지는 내 기준입니다. 천적 = 내가 그 상대에게 자주 짐(승률 40% 이하), "
                 "우세 = 내가 그 상대에게 자주 이김(65% 이상), 접전 = 승패가 비슷함(45~55%).\n"
                 "- 괄호에 배지가 없는 상대는 천적·우세·접전 어느 것도 아닙니다.",
        "rules": "- 닉네임의 *는 마스킹 처리이며 복원하거나 추측하지 마세요.\n"
                 "- 천적·우세·접전은 입력 괄호에 그 말이 있는 상대에게만 쓰세요.\n"
                 "- 경기 수·승·패·승률 외에 상대의 특징이나 전략은 지어내지 마세요.\n"
                 "- 상대를 '~한 선수', '~한 상대'로 묘사하지 말고 '내가 그 상대에게 어떤지'로 쓰세요.\n"
                 "- 자주 이기는지, 지는지, 특정 상대에게 유독 약한지 등 라이벌전 성향을 요약하세요.",
        "example": "5경기 이상 만난 상대 3명 중 1명에게는 승률 30%로 크게 밀립니다. 나머지 두 상대와는 접전입니다.",
    },
}

# data 키 → 각주 입력에 보여 줄 용어 이름 (영문 키를 모델에게 보여 주지 않는다)
HEAVY_LABELS = {
    "tr_trend_delta": "TR 추이(최근 평균 TR − 전체 평균 TR)",
    "delta_opener": "Opener 성향 차이",
    "delta_plonk": "Plonk 성향 차이",
    "delta_stride": "Stride 성향 차이",
    "delta_inf_ds": "Inf DS 성향 차이",
    "delta_app": "APP 차이(블록당 공격량)",
    "delta_weighted_app": "Weighted APP 차이",
    "delta_vs_apm": "VS/APM 차이(공격 대비 수비 비율)",
    "delta_cheese_index": "Cheese Index 차이(가비지 처리 성향)",
    "strength_split": "상대 강도별 승률 차(강한 상대 구간 − 약한 상대 구간)",
    "comeback_rate": "역전승률",
    "comeback_rate_against": "역전 허용률",
    "comeback_opportunities": "역전 기회(뒤졌던 매치 수)",
    "comeback_won": "역전승 횟수",
    "comeback_against_opportunities": "역전 허용 기회(앞섰던 매치 수)",
    "comeback_against_allowed": "역전 허용 횟수",
    "session_vs_slope": "라운드별 VS 기울기",
}


# 값 옆에 붙일 방향 (양수, 음수, 0). 부호 해석은 서버가 한다 (v3.1).
# 근거: 05 공식 해설 3·4·5·6·8절, 04 지표 명세 6.2절의 부호 의미.
HEAVY_DIRECTIONS: dict[str, tuple[str, str, str]] = {
    "tr_trend_delta": ("최근 TR이 전체 평균보다 높음, 상승세", "최근 TR이 전체 평균보다 낮음, 하락세", "전체 평균과 같음"),
    "delta_app": ("상대보다 높음", "상대보다 낮음", "상대와 같음"),
    "delta_weighted_app": ("상대보다 높음", "상대보다 낮음", "상대와 같음"),
    "delta_vs_apm": ("상대보다 높음, 수비 비중이 큼", "상대보다 낮음, 수비 비중이 작음", "상대와 같음"),
    "delta_cheese_index": ("상대보다 높음, 가비지 처리 성향이 강함", "상대보다 낮음, 가비지 처리 성향이 약함", "상대와 같음"),
    "strength_split": ("강한 상대 구간 승률이 더 높음", "강한 상대 구간 승률이 더 낮음", "구간 간 차이 없음"),
    "session_vs_slope": ("라운드가 진행될수록 오름", "라운드가 진행될수록 내림", "뚜렷한 추세 없음"),
}
PLAYSTYLE_NAMES = {"delta_opener": "Opener", "delta_plonk": "Plonk", "delta_stride": "Stride", "delta_inf_ds": "Inf DS"}
# 04 지표 명세 8.1절
PLAYSTYLE_MEANINGS = {"Opener": "초반 공격", "Plonk": "효율·가비지 활용", "Stride": "속도", "Inf DS": "다운스택"}


# ---------- v3.3 상황별 예시 ----------
def _sgn(value: object) -> str:
    """'+', '-', '0', 값이 없으면 ''."""
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return ""
    return "+" if value > 0 else "-" if value < 0 else "0"


_TWO_SIGN_EXAMPLES = {
    # (첫째 값 부호, 둘째 값 부호) → 예시. 값은 실제와 다르게.
    "attack": ("delta_app", "delta_weighted_app", {
        ("+", "+"): "블록당 공격량(APP)이 상대보다 높습니다. Weighted APP도 상대보다 높아 공격 효율에서 앞섭니다.",
        ("+", "-"): "블록당 공격량(APP)은 상대보다 높습니다. 하지만 Weighted APP는 상대보다 낮아, Cheese Index로 보정하면 공격 효율 우위가 사라집니다.",
        ("-", "+"): "블록당 공격량(APP)은 상대보다 낮습니다. 하지만 Weighted APP는 상대보다 높아, Cheese Index로 보정하면 공격 효율이 앞섭니다.",
        ("-", "-"): "블록당 공격량(APP)이 상대보다 낮습니다. Weighted APP도 상대보다 낮아 공격 효율에서 밀립니다.",
    }),
    "defense": ("delta_vs_apm", "delta_cheese_index", {
        ("+", "+"): "공격 대비 수비 비율(VS/APM)이 상대보다 높습니다. Cheese Index도 상대보다 높아 가비지 처리 성향이 강합니다.",
        ("+", "-"): "공격 대비 수비 비율(VS/APM)은 상대보다 높습니다. 반면 Cheese Index는 상대보다 낮아 가비지 처리 성향은 약합니다.",
        ("-", "+"): "공격 대비 수비 비율(VS/APM)은 상대보다 낮습니다. 반면 Cheese Index는 상대보다 높아 가비지 처리 성향은 강합니다.",
        ("-", "-"): "공격 대비 수비 비율(VS/APM)이 상대보다 낮습니다. Cheese Index도 상대보다 낮아 가비지 처리 성향이 약합니다.",
    }),
}
_ONE_SIGN_EXAMPLES = {
    "tr_trend": ("tr_trend_delta", {
        "+": "최근 경기의 TR이 전체 기간 평균보다 높습니다. 최근 들어 점수가 오르는 상승세입니다.",
        "-": "최근 경기의 TR이 전체 기간 평균보다 낮습니다. 최근 들어 점수가 내려가는 하락세입니다.",
        "0": "최근 경기의 TR이 전체 기간 평균과 같습니다. 뚜렷한 상승세나 하락세가 없습니다.",
    }),
    "strength_split": ("strength_split", {
        "+": "강한 상대 구간 승률이 약한 상대 구간보다 높습니다. 강한 상대에게 오히려 승률이 높은 드문 경우입니다.",
        "-": "강한 상대 구간 승률이 약한 상대 구간보다 낮습니다. 강한 상대를 만나면 승률이 떨어지는 편입니다.",
        "0": "강한 상대 구간과 약한 상대 구간의 승률 차이가 없습니다. 상대 강도와 상관없이 고르게 이기는 편입니다.",
    }),
    "session_vs_slope": ("session_vs_slope", {
        "+": "라운드가 진행될수록 VS가 오릅니다. 경기가 길어질수록 강해지는 흐름입니다.",
        "-": "라운드가 진행될수록 VS가 내립니다. 경기가 길어질수록 처지는 흐름입니다.",
        "0": "라운드가 진행되어도 VS에 뚜렷한 변화가 없습니다. 경기 내내 비슷한 컨디션을 유지합니다.",
    }),
}
_COMEBACK_EXAMPLES = {
    "+": "역전승률이 역전 허용률보다 높습니다. 불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다.",
    "-": "역전승률이 역전 허용률보다 낮습니다. 불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 약합니다.",
    "0": "역전승률과 역전 허용률이 같습니다. 뒤진 경기를 뒤집는 힘과 앞선 경기를 내주는 경향이 비슷합니다.",
}
# 라이벌: 배지별 예시 문장 (가짜 닉네임·가짜 숫자). 입력 순서대로 그 상대의 배지에 맞는 문장을 쓴다.
_RIVAL_EXAMPLE_NICKS = ("xy***_7", "qw**rt", "mn**o")
_RIVAL_EXAMPLES = {
    "천적": "{n}에게는 내가 자주 져서 천적입니다.",
    "우세": "{n}에게는 내가 자주 이겨 우세합니다.",
    "접전": "{n}와는 승패가 비슷한 접전입니다.",
    "-": "{n}와는 이긴 경기가 조금 더 많습니다.",
}


def pick_heavy_example(chapter_id: str, data: dict) -> str:
    """입력 값의 방향과 같은 상황의 예시를 고른다 (v3.3). 맞는 상황이 없으면 챕터 기본 예시."""
    default = HEAVY_CHAPTERS[chapter_id]["example"]
    if chapter_id in _TWO_SIGN_EXAMPLES:
        k1, k2, table = _TWO_SIGN_EXAMPLES[chapter_id]
        return table.get((_sgn(data.get(k1)), _sgn(data.get(k2))), default)
    if chapter_id in _ONE_SIGN_EXAMPLES:
        key, table = _ONE_SIGN_EXAMPLES[chapter_id]
        return table.get(_sgn(data.get(key)), default)
    if chapter_id == "comeback_rate":
        a, b = data.get("comeback_rate"), data.get("comeback_rate_against")
        if _sgn(a) and _sgn(b):
            return _COMEBACK_EXAMPLES[_sgn(a - b)]
        return default
    if chapter_id == "playstyle":
        # 예시의 이름을 베끼는 일이 많아(10/12) 실제 강한/약한 성향 이름으로 만든다. 숫자는 넣지 않는다.
        strong = [n for k, n in PLAYSTYLE_NAMES.items() if _sgn(data.get(k)) == "+"]
        weak = [n for k, n in PLAYSTYLE_NAMES.items() if _sgn(data.get(k)) == "-"]
        # 이름을 "Plonk·Stride" 처럼 묶으면 모델이 둘째 이름을 반대쪽으로 옮겨 씀(10/12) → 하나씩만
        if strong and weak:
            return (f"상대와 비교하면 {strong[0]} 성향이 강하고 {weak[0]} 성향은 약합니다. "
                    f"상대보다 {PLAYSTYLE_MEANINGS[strong[0]]} 쪽 성향이 뚜렷하다는 뜻입니다.")
        if strong:
            return (f"상대와 비교하면 {strong[0]} 성향이 강합니다. "
                    f"상대보다 {PLAYSTYLE_MEANINGS[strong[0]]} 쪽 성향이 뚜렷하다는 뜻입니다.")
        if weak:
            return (f"상대와 비교하면 {weak[0]} 성향이 약합니다. "
                    f"상대보다 {PLAYSTYLE_MEANINGS[weak[0]]} 쪽 성향이 덜하다는 뜻입니다.")
        return default
    if chapter_id == "rivals":
        sentences = []
        for nick, r in zip(_RIVAL_EXAMPLE_NICKS, (data.get("rivals") or [])[:3]):
            m, w = r.get("matches") or 0, r.get("wins") or 0
            rate = w / m if m else 0.0
            badge = _rival_badge(rate)
            if badge == "-" and rate < 0.5:
                sentences.append(f"{nick}와는 진 경기가 조금 더 많습니다.")
            else:
                sentences.append(_RIVAL_EXAMPLES[badge].format(n=nick))
        return " ".join(sentences[:2]) or default
    return default


def _signed(value: float) -> str:
    return f"{value:+g}"


def _direction(key: str, value: object) -> str | None:
    if key not in HEAVY_DIRECTIONS or not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    pos, neg, zero = HEAVY_DIRECTIONS[key]
    return pos if value > 0 else neg if value < 0 else zero


def _playstyle_lines(data: dict) -> list[str]:
    groups: dict[str, list[str]] = {"강한": [], "약한": [], "같은": []}
    for key, name in PLAYSTYLE_NAMES.items():
        v = data.get(key)
        if not isinstance(v, (int, float)) or isinstance(v, bool):
            continue
        groups["강한" if v > 0 else "약한" if v < 0 else "같은"].append(f"{name}({_signed(v)})")
    labels = {"강한": "상대보다 강한 성향", "약한": "상대보다 약한 성향", "같은": "상대와 같은 성향"}
    return [f"- {labels[g]}: {', '.join(items)}" for g, items in groups.items() if items]


def _comparison_line(data: dict) -> str | None:
    a, b = data.get("comeback_rate"), data.get("comeback_rate_against")
    if not all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in (a, b)):
        return None
    word = "높음" if a > b else "낮음" if a < b else "같음"
    return f"- 비교: 역전승률이 역전 허용률보다 {word}"


def _rival_badge(rate: float) -> str:
    """calc 05 공식 해설 7.3절 배지 기준."""
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
            rate = wins / matches if matches else 0.0
            # 승률·배지는 서버가 계산해 넣는다 (LLM 은 수치를 계산하지 않음 — v1.2 3절)
            badge = _rival_badge(rate)
            # 배지가 없으면("-") 괄호를 생략한다 — "-" 를 그대로 주면 모델이 뜻을 지어낸다 (10/12 실측)
            lines.append(f"- {r.get('nickname_masked')}: {matches}경기 {wins}승 {losses}패, "
                         f"승률 {round(rate * 100)}%" + (f" ({badge})" if badge != "-" else ""))
    elif req.chapter_id == "playstyle":
        lines += _playstyle_lines(req.data)
    else:
        for key, value in req.data.items():
            if value is None:
                continue
            direction = _direction(key, value)
            if key == "strength_split" and direction:
                direction = direction.replace("더 ", f"{round(abs(value) * 100)}%p 더 ") if value else direction
            shown = f"{_signed(value)} ({direction})" if direction else f"{value}"
            lines.append(f"- {HEAVY_LABELS.get(key, key)}: {shown}")
        if req.chapter_id == "comeback_rate" and (cmp := _comparison_line(req.data)):
            lines.append(cmp)
    if len(lines) == 1:
        lines.append("- (값 없음)")
    return "\n".join(lines)


def build_heavy_messages(req: HeavyRequest) -> list[dict[str, str]]:
    """heavy 챕터 1건의 메시지 (heavy-v3-chapter).

    system = 공통 규칙 + 이 챕터의 [용어]·[해석]·[예시]만 (다른 챕터 규칙을 섞지 않는다)
    user   = data 를 용어 이름으로 바꾼 목록
    """
    ch = HEAVY_CHAPTERS[req.chapter_id]
    system = (
        HEAVY_COMMON.format(title=ch["title"])
        + f"\n\n[용어]\n{ch['terms']}\n\n[해석]\n{ch['rules']}\n\n[예시] (값은 실제 입력과 다름)\n"
        + pick_heavy_example(req.chapter_id, req.data)
    )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": _heavy_user_content(req)},
    ]
