"""추론 서버 진입점.

실행: llm-server 폴더에서  python -m uvicorn app.main:app --port 8081
생성기 선택(Mock/Qwen)은 app/generator.py 의 환경변수 설명 참고.

경로는 백엔드 application.yml 의 llm.light-path, llm.heavy-chapter-path 와 맞춤.
오류 응답 (10/7 백엔드와 합의)
  502 LLM 출력 형식 오류  → 백엔드가 재요청
  413 LLM 입력 길이 초과  → 재요청하지 않음 (light 는 502 LLM_FORMAT_ERROR 로 종료, heavy 는 그 챕터 failed)
"""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.generator import ContextOverflowError, build_generator
from app.prompt import build_heavy_messages, build_light_messages
from app.schemas import HeavyRequest, LightRequest
from app.validation import (
    HeavyResponse,
    LightResponse,
    OutputFormatError,
    parse_heavy_output,
    process_light_output,
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


class UTF8JSONResponse(JSONResponse):
    # charset 을 명시해야 Windows PowerShell 5.1 같은 클라이언트도 한글을 깨뜨리지 않고 읽는다
    media_type = "application/json; charset=utf-8"


app = FastAPI(title="Finesse LLM 추론 서버", lifespan=lifespan, default_response_class=UTF8JSONResponse)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/comment/light", response_model=LightResponse)
def comment_light(req: LightRequest):
    # ① 요청 검증: FastAPI가 LightRequest로 바꾸면서 자동으로 한다 (실패 시 422)
    # ② 프롬프트 조립
    messages = build_light_messages(req)
    # ③ 생성 (LLM_GENERATOR 에 따라 Mock 또는 Qwen)
    result = _generate(generator.generate_light, req, messages)
    # ④ 응답 검증: 형식(앞 JSON 만 읽기) → 프롬프트 베낌 → stat 키 정리 → 내용 규칙
    try:
        resp, issues, changed = process_light_output(result.text, req.available_stats())
    except OutputFormatError as e:
        logger.warning("형식 오류 generator=%s finish=%s: %s", generator.name, result.finish_reason, e)
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from e
    if changed:
        logger.info("stat 키 정리: %s", changed)
    # 내용 문제는 고치지 않고 기록만 (3개 초과 자르기·11개 밖 제외·재요청은 백엔드 몫)
    if issues:
        logger.warning("내용 규칙 위반 generator=%s: %s", generator.name, issues)
    return resp


@app.post("/v1/comment/heavy-chapter", response_model=HeavyResponse)
def comment_heavy_chapter(req: HeavyRequest):
    # ① 요청 검증: chapter_id 가 8개 중 하나가 아니거나 data 가 객체가 아니면 422
    # ② 프롬프트 조립
    messages = build_heavy_messages(req)
    # ③ 생성
    result = _generate(generator.generate_heavy, req, messages)
    # ④ 응답 검증 (v1.2 5.3절 형식 오류 4가지 → 502, 백엔드가 그 챕터만 재요청)
    try:
        return parse_heavy_output(result.text, req.chapter_id)
    except OutputFormatError as e:
        logger.warning("heavy 형식 오류 chapter=%s generator=%s finish=%s: %s",
                       req.chapter_id, generator.name, result.finish_reason, e)
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from e


def _generate(generate, req, messages):
    try:
        return generate(req, messages)
    except ContextOverflowError as e:
        # 같은 요청을 다시 보내도 결과가 같으므로 재요청 대상(502)과 구분한다 (10/7 백엔드 합의)
        logger.warning("입력 길이 초과 generator=%s: %s", generator.name, e)
        raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
