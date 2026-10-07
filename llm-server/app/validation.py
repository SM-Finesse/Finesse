"""응답 검증 (생성기 출력 -> 백엔드로 보내기 전). light 와 heavy.

근거: LLM/AI 파트 설계 v1.2 5.2절(응답 스키마), 6절·9.2절(처리 규칙),
      12.1절(형식 준수율: 유효 JSON + 키 구성 + 정확히 3개 + stat이 11개 키 안)

두 단계로 나눈다.
  1) parse_light_output : 형식(구조) 검사. 통과 못 하면 OutputFormatError.
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


def parse_light_output(text: str) -> LightResponse:
    """생성기가 낸 문자열을 응답 객체로 바꾼다. 형식이 틀리면 OutputFormatError."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise OutputFormatError(f"유효한 JSON이 아님: {e}") from e
    try:
        return LightResponse.model_validate(data)
    except ValidationError as e:
        first = e.errors()[0]
        raise OutputFormatError(f"키 구성 오류: {first['loc']} {first['msg']}") from e


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
        data = json.loads(text)
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
