"""추론 서버 진입점.

실행: llm-server 폴더에서  python -m uvicorn app.main:app --port 8081
생성기 선택(Mock/Qwen)은 app/generator.py 의 환경변수 설명 참고.

경로는 백엔드 application.yml 의 llm.light-path 와 맞춤 (10/7 백엔드 연동 확인).
오류 응답 방식(형식 오류 시 502)은 백엔드와 확정 필요(v1.2 9.1절 TBD).
"""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.generator import build_generator
from app.prompt import build_light_messages
from app.schemas import LightRequest
from app.validation import (
    LightResponse,
    OutputFormatError,
    check_light_output,
    parse_light_output,
)

# uvicorn 이 이미 설정해 둔 로거를 써서, 서버 창에 우리 로그가 함께 보이게 한다
logger = logging.getLogger("uvicorn.error")

generator = build_generator()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    logger.info("생성기: %s", generator.name)
    if hasattr(generator, "warmup"):
        generator.warmup()  # 기동 직후 첫 요청 지연 대비 (v1.2 4.3절)
        logger.info("워밍업 완료")
    yield


app = FastAPI(title="Finesse LLM 추론 서버", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/comment/light", response_model=LightResponse)
def comment_light(req: LightRequest):
    # ① 요청 검증: FastAPI가 LightRequest로 바꾸면서 자동으로 한다 (실패 시 422)
    # ② 프롬프트 조립
    messages = build_light_messages(req)
    # ③ 생성 (LLM_GENERATOR 에 따라 Mock 또는 Qwen)
    result = generator.generate_light(req, messages)
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