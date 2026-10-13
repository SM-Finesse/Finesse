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
  LLM_LIGHT_PROMPT (기본 v3): v3 = 번호·줄 형식(app/light_v3.py), v2 = 예전 JSON 방식
  LLM_LIGHT_MODE (기본 a): a | b | c = light-v4(선정 규칙·라벨, app/light_pipeline.py), v3 = 라벨 없는 light-v3-lines
      LLM_LIGHT_ATTEMPTS, LLM_LIGHT_BUDGET_SEC, LLM_LIGHT_FALLBACK, LLM_LIGHT_RANGES 설명은 app/light_pipeline.py
  LLM_LIGHT_SIZE_HINT, LLM_LIGHT_SHUFFLE (기본 off): light-v3(LLM_LIGHT_MODE=v3) 선택 보조 — PM 확인 전 측정용
  LLM_HEAVY_TEMPLATE_CHAPTERS (기본 rivals, v3.5): LLM 을 부르지 않고 바로 서버 문장을 쓰는 챕터 (쉼표 구분,
      빈 값이면 모든 챕터에 LLM 사용). 10/12 실측에서 rivals 는 프롬프트를 바꿔도 2/10 이라 서버 문장으로 둔다.
"""
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.fallback import build_fallback
from app.generator import ContextOverflowError, build_generator
from app.light_pipeline import LightFailure, LightSettings, run_light
from app.light_v3 import build_light_v3, process_light_v3
from app.prompt import build_heavy_messages, build_light_messages
from app.schemas import HeavyRequest, LightRequest
from app.validation import (
    HeavyResponse,
    Highlight,
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
    if os.getenv("LLM_LIGHT_PROMPT", "v3").lower() == "v2":
        return _comment_light_v2(req)
    if os.getenv("LLM_LIGHT_MODE", "a").strip().lower() != "v3":
        return _comment_light_v4(req)
    # ② 프롬프트 조립 (light-v3: 번호·한국어 이름·방향을 붙인 후보 목록, app/light_v3.py)
    ctx = build_light_v3(req)
    # ③ 생성: 모델은 "번호 | 문장" 3줄 + "총평 | 문장"만 쓴다
    result = _generate(generator.generate_light_text, req, ctx.messages)
    # ④ 줄 → 하이라이트. 틀린 줄만 빼고, 쓸 수 있는 것이 하나도 없으면 502(백엔드 재요청)
    try:
        out = process_light_v3(result.text, ctx)
    except ValueError as e:
        logger.warning("light 형식 오류 generator=%s finish=%s: %s", generator.name, result.finish_reason, e)
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from e
    if out.dropped:
        logger.warning("light 뺀 줄 %s", out.dropped)
    logger.info("light 선택 %s (후보 순서 %s)", [k for k, _ in out.highlights], ctx.order)
    return LightResponse(light_summary=out.summary,
                         highlights=[Highlight(stat=k, sentence=s) for k, s in out.highlights])


def _comment_light_v4(req: LightRequest):
    """light-v4 (A/B/C). 단계 나누기·재시도·규칙 검사는 app/light_pipeline.py."""
    try:
        out = run_light(req, generator, LightSettings.from_env())
    except ContextOverflowError as e:
        logger.warning("입력 길이 초과 generator=%s: %s", generator.name, e)
        raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
    except LightFailure as e:
        o = e.outcome
        logger.warning("light-%s 형식 오류 generator=%s %.1fs: %s / 호출 %s", o.mode, generator.name, o.elapsed, e,
                       [(c.kind, c.attempt, round(c.sec, 1), c.finish, c.result) for c in o.calls])
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from e
    for c in out.calls:
        logger.info("light-%s %s#%d %.1fs finish=%s → %s", out.mode, c.kind, c.attempt, c.sec, c.finish, c.result)
    if out.sentence_dropped or out.sentence_fallback or out.budget_stops:
        logger.warning("light-%s 문장 뺌 %s / 서버 문장 %s / 시간 예산으로 생략 %s", out.mode, out.sentence_dropped,
                       out.sentence_fallback, out.budget_stops)
    logger.info("light-%s 선택 %s (규칙 정답 %s) 총평=%s %.1fs", out.mode, out.picks, out.expected,
                "서버" if out.summary_fallback else "모델", out.elapsed)
    return LightResponse(light_summary=out.summary,
                         highlights=[Highlight(stat=k, sentence=s) for k, s in out.highlights])


def _comment_light_v2(req: LightRequest):
    """light-baseline-v2-trsum (llm-base-v1 까지의 방식). LLM_LIGHT_PROMPT=v2 로 되돌릴 수 있다."""
    messages = build_light_messages(req)
    result = _generate(generator.generate_light, req, messages)
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
    # ② 프롬프트 조립 (챕터별 용어·해석 + 입력 방향에 맞는 예시)
    template_chapters = {c.strip() for c in os.getenv("LLM_HEAVY_TEMPLATE_CHAPTERS", "rivals").split(",") if c.strip()}
    if req.chapter_id in template_chapters:
        logger.info("heavy 서버 문장 사용 chapter=%s (LLM_HEAVY_TEMPLATE_CHAPTERS)", req.chapter_id)
        return HeavyResponse(chapter_id=req.chapter_id, footnote=build_fallback(req.chapter_id, req.data))
    messages = build_heavy_messages(req)
    attempts = max(1, int(os.getenv("LLM_HEAVY_ATTEMPTS", "2")))
    fallback = os.getenv("LLM_HEAVY_FALLBACK", "on").lower() != "off"
    last_error: OutputFormatError | None = None
    for attempt in range(1, attempts + 1):
        # ③ 생성 → ④ 문장 다듬기·검사 (형식·숫자·방향·베낌)
        result = _generate(generator.generate_heavy, req, messages)
        try:
            return process_heavy_output(result.text, req.chapter_id, req.data)
        except OutputFormatError as e:
            last_error = e
            logger.warning("heavy 검사 실패 %d/%d chapter=%s generator=%s finish=%s: %s",
                           attempt, attempts, req.chapter_id, generator.name, result.finish_reason, e)
    if fallback:
        # ⑤ 모두 실패 → 서버 대체 문장 (각주가 비지 않게). 파인튜닝 전후 비교용으로 로그를 남긴다.
        logger.warning("heavy 대체 문장 사용 chapter=%s", req.chapter_id)
        return HeavyResponse(chapter_id=req.chapter_id, footnote=build_fallback(req.chapter_id, req.data))
    raise HTTPException(status_code=502, detail="LLM 출력 형식 오류") from last_error


def _generate(generate, req, messages):
    try:
        return generate(req, messages)
    except ContextOverflowError as e:
        # 같은 요청을 다시 보내도 결과가 같으므로 재요청 대상(502)과 구분한다 (10/7 백엔드 합의)
        logger.warning("입력 길이 초과 generator=%s: %s", generator.name, e)
        raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
