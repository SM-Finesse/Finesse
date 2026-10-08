"""응답 검증 (생성기 출력 -> 백엔드로 보내기 전). light 와 heavy.

근거: LLM/AI 파트 설계 v1.2 5.2절(응답 스키마), 6절·9.2절(처리 규칙),
      12.1절(형식 준수율: 유효 JSON + 키 구성 + 정확히 3개 + stat이 11개 키 안)

두 단계로 나눈다.
  1) parse_light_output : 형식(구조) 검사. 통과 못 하면 OutputFormatError.
     find_prompt_echo   : 시스템 프롬프트를 베낀 출력 찾기 (있으면 서버가 502 → 백엔드 재요청)
     normalize_stats    : 점 경로 stat 을 평탄 키로 정리
  2) check_light_output : 내용 규칙 검사. 문제 목록만 돌려주고 응답은 고치지 않는다.
     (3개 초과 자르기·11개 밖 제외·재요청은 백엔드 몫 — v1.2 6절)
"""
import json

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.schemas import CANDIDATE_KEYS


class _StrictOut(BaseModel):
    # forbid: 정해진 키 외의 키가 오면 형식 오류 (예: 구버전 summary_hint)
    # str_strip_whitespace: 공백만 있는 문장은 빈 문장으로 본다
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Highlight(_StrictOut):
    stat: str
    sentence: str = Field(min_length=1)


class LightResponse(_StrictOut):
    light_summary: str = Field(min_length=1)
    highlights: list[Highlight]


class OutputFormatError(ValueError):
    """생성기 출력이 응답 형식에 맞지 않을 때."""


def _load_json(text: str, lenient: bool) -> object:
    """문자열을 JSON으로 읽는다.

    lenient=True: 첫 번째 '{' 부터 JSON 객체 하나만 읽고, 그 뒤에 붙은 글은 무시한다.
      (베이스 Qwen 이 JSON 뒤에 설명·두 번째 JSON 을 덧붙여 502 가 나던 문제, 10/7 11번 로그 "Extra data")
    lenient=False: 전체가 정확히 JSON 하나여야 한다 (10/7 이전 기준, 비교 측정용).
    """
    if not lenient:
        return json.loads(text)
    start = text.find("{")
    if start < 0:
        raise json.JSONDecodeError("JSON 객체가 없음", text, 0)
    obj, _end = json.JSONDecoder().raw_decode(text, start)
    return obj


def parse_light_output(text: str, lenient: bool = True) -> LightResponse:
    """생성기가 낸 문자열을 응답 객체로 바꾼다. 형식이 틀리면 OutputFormatError."""
    try:
        data = _load_json(text, lenient)
    except json.JSONDecodeError as e:
        raise OutputFormatError(f"유효한 JSON이 아님: {e}") from e
    try:
        return LightResponse.model_validate(data)
    except ValidationError as e:
        first = e.errors()[0]
        raise OutputFormatError(f"키 구성 오류: {first['loc']} {first['msg']}") from e


# 시스템 프롬프트에만 있는 문구. 출력에 이게 있으면 프롬프트를 베낀 것으로 본다.
# (10/7 백엔드 보고: icly 결과 총평에 "당신은 테트리스 게임 TETR.IO의 ..." 가 그대로 나옴)
PROMPT_ECHO_MARKERS = (
    "당신은 테트리스",
    "어시스턴트입니다",
    "[해석 규칙]",
    "[선정·서술 규칙]",
    "[출력 형식]",
    "JSON으로만 응답",
    "2-3문장 요약",
    "지표 키",
)


def find_prompt_echo(resp: LightResponse) -> list[str]:
    """요약·문장에서 발견된 프롬프트 문구 목록. 비어 있지 않으면 형식 오류(502)로 돌려 재요청을 받는다."""
    texts = [resp.light_summary, *(h.sentence for h in resp.highlights)]
    return [m for m in PROMPT_ECHO_MARKERS if any(m in t for t in texts)]


def normalize_stats(resp: LightResponse) -> list[str]:
    """점 경로 stat(예: playstyle_relative.delta_plonk)을 평탄 키(delta_plonk)로 고친다.

    마지막 조각이 후보 11개 중 하나일 때만 고친다. 고친 목록을 돌려준다(로그용).
    ※ v1.2 5.2절은 "11개 밖 stat 은 백엔드가 그 하이라이트만 제외"로 되어 있음.
      서버에서 미리 고치는 것은 10/8 추가 — 팀 확인 필요.
    """
    changed = []
    for h in resp.highlights:
        if h.stat not in CANDIDATE_KEYS and "." in h.stat:
            tail = h.stat.rsplit(".", 1)[1]
            if tail in CANDIDATE_KEYS:
                changed.append(f"{h.stat}->{tail}")
                h.stat = tail
    return changed


def process_light_output(text: str, available: dict[str, float]) -> tuple[LightResponse, list[str], list[str]]:
    """서버가 light 출력에 하는 처리 전체 (서버와 실험 스크립트가 같은 함수를 쓴다).

    형식 검사(앞 JSON 만) → 프롬프트 베낌 검사 → stat 키 정리 → 내용 규칙 검사.
    형식 문제면 OutputFormatError. 아니면 (응답, 내용 문제 목록, 키 정리 목록).
    """
    resp = parse_light_output(text)
    echo = find_prompt_echo(resp)
    if echo:
        raise OutputFormatError(f"시스템 프롬프트를 베낀 출력: {echo}")
    changed = normalize_stats(resp)
    return resp, check_light_output(resp, available), changed


def check_light_output(resp: LightResponse, available: dict[str, float]) -> list[str]:
    """내용 규칙 위반 목록을 돌려준다. 빈 목록이면 통과.

    available: 요청에서 값이 있던 후보 (LightRequest.available_stats() 결과)
    """
    issues: list[str] = []
    if len(resp.highlights) != 3:
        issues.append(f"count:{len(resp.highlights)}")
    seen: set[str] = set()
    for h in resp.highlights:
        if h.stat not in CANDIDATE_KEYS:
            issues.append(f"unknown_stat:{h.stat}")
        elif h.stat not in available:
            issues.append(f"unavailable_stat:{h.stat}")
        if h.stat in seen:
            issues.append(f"duplicate_stat:{h.stat}")
        seen.add(h.stat)
    return issues


# ---------------- heavy ----------------
class HeavyResponse(_StrictOut):
    chapter_id: str
    footnote: str = Field(min_length=1)


def parse_heavy_output(text: str, chapter_id: str) -> HeavyResponse:
    """heavy 형식 검사 (v1.2 5.3절 "형식 오류" 4가지).

    유효한 JSON 아님 / 두 필드 중 하나라도 없음 / chapter_id 불일치 / footnote 빈 문자열·공백
    → OutputFormatError (백엔드가 그 챕터만 재요청)
    """
    try:
        data = _load_json(text, lenient=True)
    except json.JSONDecodeError as e:
        raise OutputFormatError(f"유효한 JSON이 아님: {e}") from e
    try:
        resp = HeavyResponse.model_validate(data)
    except ValidationError as e:
        first = e.errors()[0]
        raise OutputFormatError(f"키 구성 오류: {first['loc']} {first['msg']}") from e
    if resp.chapter_id != chapter_id:
        raise OutputFormatError(f"chapter_id 불일치: 요청 {chapter_id}, 출력 {resp.chapter_id}")
    return resp
