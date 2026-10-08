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
HEAVY_PROMPT_VERSION = "heavy-draft-v1"  # v1.2 7.3절 초안 그대로. 팀 검토 후 문구가 바뀌면 올린다.

HEAVY_SYSTEM_PROMPT = """당신은 테트리스 게임 TETR.IO의 전적 데이터를 분석해 한국어로 짧은 각주를
작성하는 어시스턴트입니다. 입력으로 chapter_id와 해당 챕터의 data가
주어집니다. 이 챕터에 대한 각주를 1-2문장으로 작성하세요.

[규칙]
1. data에 있는 값만 인용하세요. data에 없는 수치·판수·비율을 만들지 마세요.
2. delta_* 값이 양수면 상대보다 그 수치·성향이 강함을 뜻합니다.
3. 플레이스타일 값끼리 비교하거나 절대값·백분위로 서술하지 마세요.
4. 플레이어를 한 유형으로 규정하지 마세요.
5. strength_split은 보통 음수이며, 음수 폭이 클수록 강한 상대에게 크게
   무너진다는 뜻입니다.
6. 역전 지표에서 몇 판 차이로 뒤졌는지는 쓰지 마세요.
7. session_vs_slope가 음수면 라운드가 진행될수록 처지는 경향, 양수면
   강해지는 경향, 0이면 뚜렷한 추세가 없다는 뜻입니다.
8. 추정 TR 등 data에 없는 지표를 언급하지 마세요.

[출력 형식]
다음 JSON으로만 응답하세요. chapter_id는 입력값을 그대로 쓰세요.
{"chapter_id": "입력과 같은 값", "footnote": "1-2문장"}"""

# 라이벌 챕터 보충 지시 (v1.2 7.4절 "프롬프트 지침")
RIVALS_GUIDE = """아래는 5경기 이상 만난 상대 목록입니다. 각 닉네임의 *는 마스킹 처리이며
복원하거나 추측하지 마세요. 이 데이터를 바탕으로 유저의 라이벌전 성향
(자주 이기는지, 지는지, 특정 상대에게 유독 약한지 등)을 1-2문장으로
요약하세요."""

# heavy 디코딩 (v1.2 7.2절: temperature·top_p 는 light 와 같고 max_tokens 119)
HEAVY_DECODING = {
    "temperature": 0.4,
    "top_p": 0.9,
    "max_tokens": 119,
}


def build_heavy_messages(req: HeavyRequest) -> list[dict[str, str]]:
    """heavy 챕터 1건의 메시지. user 메시지 = 요청 {chapter_id, data} 를 압축 JSON으로 그대로.

    라이벌 챕터만 v1.2 7.4절 지침을 시스템 프롬프트 뒤에 붙인다.
    (다른 챕터별 보충 지시는 data 구성이 확정된 뒤 추가 — v1.2 7.3절)
    """
    system = HEAVY_SYSTEM_PROMPT
    if req.chapter_id == "rivals":
        system = f"{system}\n\n{RIVALS_GUIDE}"
    user_content = json.dumps(req.model_dump(), ensure_ascii=False, separators=(",", ":"))
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user_content},
    ]
