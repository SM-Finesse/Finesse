"""추론 서버 진입점.

실행: llm-server 폴더에서  python -m uvicorn app.main:app --port 8081
생성기 선택(Mock/Qwen)은 app/generator.py 의 환경변수 설명 참고.

경로는 백엔드 application.yml 의 llm.light-path, llm.heavy-chapter-path 와 맞춤.
오류 응답 (10/7 백엔드와 합의)
  502 LLM 출력 형식 오류  → 백엔드가 재요청
  413 LLM 입력 길이 초과  → 재요청하지 않음 (light 는 502 LLM_FORMAT_ERROR 로 종료, heavy 는 그 챕터 failed)

heavy 대체 문장 (v3.4, 10/12)
  LLM_HEAVY_ATTEMPTS (기본 2): 한 요청 안에서 LLM 으로 시도하는 횟수
  LLM_HEAVY_FALLBACK (기본 on): on 이면 모두 실패했을 때 서버가 만든 대체 문장으로 200, off 면 예전처럼 502
  LLM_HEAVY_TEMPLATE_CHAPTERS (기본 rivals, v3.5): LLM 을 부르지 않고 바로 서버 문장을 쓰는 챕터 (쉼표 구분,
      빈 값이면 모든 챕터에 LLM 사용). 10/12 실측에서 rivals 는 프롬프트를 바꿔도 2/10 이라 서버 문장으로 둔다.

heavy 시간 예산 (10/20, 백엔드 회신: 챕터 호출 10초는 백엔드가 요청을 보낸 순간부터 — 서버 안 대기 포함)
  LLM_HEAVY_DEADLINE_SEC (기본 9): 요청을 받은 시각부터 이 시간 안에 응답한다.
  LLM_HEAVY_GEN_RESERVE_SEC (기본 3.5): 생성 1회에 남겨 둘 시간(최악 약 3초 + 여유). 남은 시간이 이보다 적으면
      생성(재생성 포함)을 시작하지 않고, 다른 요청 때문에 그 전까지 차례가 오지 않아도 바로 대체 문장을 보낸다.
  응답의 fallback: 서버 대체 문장이면 true (백엔드 요청, 캐시 제외용).
"""
import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.fallback import build_fallback
from app.generator import ContextOverflowError, GeneratorBusyError, build_generator
from app.prompt import build_heavy_messages, build_light_messages
from app.schemas import HeavyRequest, LightRequest
from app.validation import (
    HeavyResponse,
    LightResponse,
    OutputFormatError,
    process_heavy_output,
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
    t0 = time.monotonic()                       # 시간 예산은 요청을 받은 시각부터 (서버 안 대기 포함)
    # ① 요청 검증: chapter_id 가 8개 중 하나가 아니거나 data 가 객체가 아니면 422
    # ② 프롬프트 조립 (챕터별 용어·해석 + 입력 방향에 맞는 예시)
    template_chapters = {c.strip() for c in os.getenv("LLM_HEAVY_TEMPLATE_CHAPTERS", "rivals").split(",") if c.strip()}
    if req.chapter_id in template_chapters:
        logger.info("heavy 서버 문장 사용 chapter=%s (LLM_HEAVY_TEMPLATE_CHAPTERS)", req.chapter_id)
        return HeavyResponse(chapter_id=req.chapter_id, footnote=build_fallback(req.chapter_id, req.data),
                             fallback=True)
    messages = build_heavy_messages(req)
    attempts = max(1, int(os.getenv("LLM_HEAVY_ATTEMPTS", "2")))
    fallback = os.getenv("LLM_HEAVY_FALLBACK", "on").lower() != "off"
    deadline = float(os.getenv("LLM_HEAVY_DEADLINE_SEC", "9"))
    reserve = float(os.getenv("LLM_HEAVY_GEN_RESERVE_SEC", "3.5"))
    last_error: Exception | None = None
    reason = "검사 실패"
    for attempt in range(1, attempts + 1):
        # 남은 시간 = 예산 - 지난 시간. 생성 1회(reserve)를 할 시간이 없으면 시작하지 않는다.
        # 대체 문장이 꺼져 있으면(off) 예전처럼 시간과 상관없이 끝까지 시도한다.
        wait_limit = deadline - (time.monotonic() - t0) - reserve
        if fallback and wait_limit < 0:
            reason = f"시간 예산 부족({time.monotonic() - t0:.1f}s 지남)"
            logger.warning("heavy 재생성 건너뜀 %d/%d chapter=%s: %s", attempt, attempts, req.chapter_id, reason)
            break
        # ③ 생성 → ④ 문장 다듬기·검사 (형식·숫자·방향·베낌)
        try:
            result = _generate(generator.generate_heavy, req, messages,
                               lock_timeout=wait_limit if fallback else None)
        except GeneratorBusyError as e:
            reason = f"차례 대기 초과({e})"
            logger.warning("heavy 생성 못 함 %d/%d chapter=%s: %s", attempt, attempts, req.chapter_id, reason)
            last_error = e
            break
        try:
            resp = process_heavy_output(result.text, req.chapter_id, req.data)
            logger.info("heavy 응답 chapter=%s %.1fs 시도 %d", req.chapter_id, time.monotonic() - t0, attempt)
            return resp
        except OutputFormatError as e:
            last_error = e
            logger.warning("heavy 검사 실패 %d/%d chapter=%s generator=%s finish=%s: %s",
                           attempt, attempts, req.chapter_id, generator.name, result.finish_reason, e)
    if fallback:
        # ⑤ 모두 실패·시간 부족 → 서버 대체 문장 (각주가 비지 않게). 파인튜닝 전후 비교용으로 로그를 남긴다.
        logger.warning("heavy 대체 문장 사용 chapter=%s %.1fs (%s)", req.chapter_id, time.monotonic() - t0, reason)
        return HeavyResponse(chapter_id=req.chapter_id, footnote=build_fallback(req.chapter_id, req.data),
                             fallback=True)
    raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from last_error


def _generate(generate, req, messages, **kwargs):
    try:
        return generate(req, messages, **kwargs)
    except ContextOverflowError as e:
        # 같은 요청을 다시 보내도 결과가 같으므로 재요청 대상(502)과 구분한다 (10/7 백엔드 합의)
        logger.warning("입력 길이 초과 generator=%s: %s", generator.name, e)
        raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
