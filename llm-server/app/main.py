"""추론 서버 진입점.

실행: llm-server 폴더에서  python -m uvicorn app.main:app --port 8081

경로는 백엔드가 가정해 둔 값(backend application.yml의 llm.light-path)에 맞췄다.
최종 경로·오류 응답 방식은 백엔드와 확정 필요(v1.2 9.1절 TBD).
"""
import logging

from fastapi import FastAPI, HTTPException

from app.generator import MockGenerator
from app.schemas import LightRequest
from app.validation import (
    LightResponse,
    OutputFormatError,
    check_light_output,
    parse_light_output,
)

logger = logging.getLogger("llm-server")

app = FastAPI(title="Finesse LLM 추론 서버")
generator = MockGenerator()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/comment/light", response_model=LightResponse)
def comment_light(req: LightRequest):
    # ① 요청 검증: FastAPI가 LightRequest로 바꾸면서 자동으로 한다 (실패 시 422)
    # ② 프롬프트 조립: 다음 단계에서 추가 (Mock은 프롬프트를 쓰지 않음)
    # ③ 생성
    result = generator.generate_light(req)
    # ④ 응답 검증 - 형식
    try:
        resp = parse_light_output(result.text)
    except OutputFormatError as e:
        logger.warning("형식 오류 generator=%s finish=%s: %s", generator.name, result.finish_reason, e)
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from e
    # ④ 응답 검증 - 내용 (고치지 않고 기록만. 자르기·재요청은 백엔드 몫)
    issues = check_light_output(resp, req.available_stats())
    if issues:
        logger.warning("내용 규칙 위반 generator=%s: %s", generator.name, issues)
    return resp