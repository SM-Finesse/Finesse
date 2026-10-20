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

light 6회 호출 (light-6call, 34번 「라이트 뷰 LLM 호출 구조」 PM 확정안 10/14) — 단계를 잇는 것은 백엔드
  POST /v1/light/judge     1단계 판정   {candidates:[{stat,value,q1,q3}]}      → {judgments:[{stat,zone,deviation}]}
  POST /v1/light/select    2단계 선정   {judgments:[...]}                        → {stats:[...]} (우선순위 순, 3개 초과 가능)
  POST /v1/light/sentence  3단계 개별   {stat,value,zone}                        → {stat,sentence}
  POST /v1/light/summary   3단계 기본   {items:[{stat,value,zone}] ≤3}           → {light_summary}
  검증 ①②③(app/light6.py)에 실패하면 502 → 백엔드가 그 호출만 재요청 (34번 5절). 413 은 기존과 같음.
  LLM_LIGHT6_ATTEMPTS (기본 1): 서버 안에서 다시 생성하는 횟수. 1 이면 재요청은 전부 백엔드가 한다.
  LLM_LIGHT_MODE=six : 기존 /v1/comment/light 한 번 호출로 6회 흐름을 서버 안에서 순서대로 실행 (임시·연동 시험용,
      Q1·Q3 는 config/light_quartiles.json, 3단계를 동시에 못 하므로 백엔드 구현보다 느림)

heavy 시간 예산 (10/20, 백엔드 회신: 챕터 호출 10초는 백엔드가 요청을 보낸 순간부터 — 서버 안 대기 포함)
  LLM_HEAVY_DEADLINE_SEC (기본 9): 요청을 받은 시각부터 이 시간 안에 응답한다.
  LLM_HEAVY_GEN_RESERVE_SEC (기본 3.5): 생성 1회에 남겨 둘 시간(최악 약 3초 + 여유). 남은 시간이 이보다 적으면
      생성(재생성 포함)을 시작하지 않고, 다른 요청 때문에 그 전까지 차례가 오지 않아도 바로 대체 문장을 보낸다.
  응답의 fallback: 서버 대체 문장이면 true (백엔드 요청, 캐시 제외용).
"""
import logging
import os
import random
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app import light_comment as lc
from app.fallback import build_fallback
from app.generator import ContextOverflowError, GeneratorBusyError, build_generator
from app.light6 import (
    LIGHT6_DECODING,
    StageError,
    build_judge,
    build_select,
    parse_judge,
    parse_select,
)
from app.light6_flow import Flow6Settings, run_light6
from app.light_pipeline import LightFailure, LightSettings, run_light
from app.light_v3 import build_light_v3, process_light_v3
from app.prompt import build_heavy_messages, build_light_messages
from app.schemas import (
    CANDIDATE_KEYS,
    HeavyRequest,
    JudgeRequest,
    LightRequest,
    SelectRequest,
    SentenceRequest,
    SummaryRequest,
)
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
    mode = os.getenv("LLM_LIGHT_MODE", "a").strip().lower()
    if mode == "six":
        return _comment_light_six(req)
    if mode != "v3":
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


# ---------------- light 6회 호출: 단계별 엔드포인트 ----------------
def _light6_attempts() -> int:
    return max(1, int(os.getenv("LLM_LIGHT6_ATTEMPTS", "1")))


def _light6_generate(stage: str, messages, parse):
    """생성 → 해석·검증. 통과하면 parse 결과, LLM_LIGHT6_ATTEMPTS 번 모두 실패하면 502."""
    last = ""
    for attempt in range(1, _light6_attempts() + 1):
        try:
            result = generator.generate_text(f"light6:{stage}", messages, LIGHT6_DECODING[stage])
        except ContextOverflowError as e:
            logger.warning("light6 %s 입력 길이 초과: %s", stage, e)
            raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
        try:
            return parse(result.text)
        except StageError as e:
            last = str(e)
            logger.warning("light6 %s 검증 실패 %d/%d finish=%s: %s", stage, attempt, _light6_attempts(),
                           result.finish_reason, e)
    raise HTTPException(status_code=502, detail=f"LLM 출력 형식 오류 ({stage}: {last})")


@app.post("/v1/light/judge")
def light_judge(req: JudgeRequest):
    given = {c.stat: c for c in req.candidates}
    if len(given) != len(req.candidates):
        raise HTTPException(status_code=422, detail="같은 stat 이 두 번 들어옴")
    # 후보 고정 순서(34번 3.0절 동점 규칙과 같은 순서). 요청에 없는 지표는 판정하지 않는다.
    cands = [(k, given[k].value, given[k].q1, given[k].q3) for k in CANDIDATE_KEYS if k in given]
    if any(q3 <= q1 for _, _, q1, q3 in cands):
        raise HTTPException(status_code=422, detail="Q1 < Q3 이어야 함")

    def parse(text):
        r = parse_judge(text, cands)
        if r.errors:                                   # 검증 ① — 정답과 다르면 덮어쓰지 않고 재요청(502)
            raise StageError(f"판정 불일치 {len(r.errors)}개 {r.errors[:3]}")
        return {"judgments": [{"stat": k, "zone": r.judgments[k][0], "deviation": r.judgments[k][1]}
                              for k, *_ in cands]}

    return _light6_generate("judge", build_judge(cands), parse)


@app.post("/v1/light/select")
def light_select(req: SelectRequest):
    order = {k: i for i, k in enumerate(CANDIDATE_KEYS)}
    jl = sorted(((j.stat, j.zone, j.deviation) for j in req.judgments), key=lambda t: order[t[0]])
    target = min(3, sum(1 for _, z, _ in jl if z != "없음"))
    if target == 0:
        raise HTTPException(status_code=422, detail="고를 수 있는 지표가 없음 (모두 '없음')")

    def parse(text):
        r = parse_select(text, jl)                     # 검증 ② — 11개 키·서로 다름·'없음' 아님
        if len(r.stats) < target:
            raise StageError(f"선정 {len(r.stats)}개 < {target} (고름 {r.raw}, 뺀 것 {r.dropped})")
        return {"stats": r.stats}

    return _light6_generate("select", build_select(jl), parse)


# ---- 개별·기본 코멘트 (light-v5, 31번 10/16 개정판: 판정·선정은 calc, LLM 은 설명만) ----
def _light_comment_attempts() -> int:
    return max(1, int(os.getenv("LLM_LIGHT_COMMENT_ATTEMPTS", "2")))


def _light_comment(stage: str, messages, parse, fallback: str, t0: float) -> tuple[str, bool]:
    """생성 → 검사. 통과하면 (문장, False). 정해진 횟수 모두 실패하거나 시간이 부족하면 대체 문장 (문장, True).

    시간 예산은 헤비와 같은 방식 (요청을 받은 시각 t0 부터):
      LLM_LIGHT_COMMENT_DEADLINE_SEC (기본 9) — 백엔드 호출 1회 제한이 정해지면 그보다 1초 짧게 맞춘다.
      LLM_LIGHT_COMMENT_RESERVE_SEC (기본 sentence 3.5 / summary 4.5) — 생성 1회에 남길 시간 (max_tokens 119 / 160).
    """
    deadline = float(os.getenv("LLM_LIGHT_COMMENT_DEADLINE_SEC", "9"))
    reserve = float(os.getenv("LLM_LIGHT_COMMENT_RESERVE_SEC", "3.5" if stage == "sentence" else "4.5"))
    reason = "검사 실패"
    for attempt in range(1, _light_comment_attempts() + 1):
        wait_limit = deadline - (time.monotonic() - t0) - reserve
        if wait_limit < 0:
            reason = f"시간 예산 부족({time.monotonic() - t0:.1f}s 지남)"
            break
        try:
            result = generator.generate_text(f"light5:{stage}", messages, lc.LIGHT_COMMENT_DECODING[stage],
                                             lock_timeout=wait_limit)
        except ContextOverflowError as e:
            reason = f"입력 길이 초과({e})"
            break
        except GeneratorBusyError as e:
            reason = f"차례 대기 초과({e})"
            break
        s, why = parse(result.text)
        if s:
            logger.info("light %s 응답 %.1fs 시도 %d", stage, time.monotonic() - t0, attempt)
            return s, False
        logger.warning("light %s 검사 실패 %d/%d finish=%s: %s | %r", stage, attempt, _light_comment_attempts(),
                       result.finish_reason, why, result.text[:200])
    logger.warning("light %s 대체 문장 사용 %.1fs (%s)", stage, time.monotonic() - t0, reason)
    return fallback, True


def _zone_or_422(stat: str, value: float, zone: str | None) -> str:
    if zone == "없음":
        raise HTTPException(status_code=422, detail=f"{stat}: 영역 '없음'은 코멘트를 쓸 수 없음")
    return lc.resolve_zone(stat, value, zone)


@app.post("/v1/light/sentence")
def light_sentence(req: SentenceRequest):
    t0 = time.monotonic()
    zone = _zone_or_422(req.stat, req.value, req.zone)
    rng = random.Random()
    phrase = lc.pick_phrase(lc.state(req.stat, req.value, zone), rng)
    s, used_fallback = _light_comment(
        "sentence", lc.build_sentence(req.stat, req.value, zone, rng),
        lambda t: lc.parse_sentence(t, req.stat, req.value, zone),
        lc.build_sentence_text(req.stat, req.value, zone, phrase), t0)
    return {"stat": req.stat, "sentence": s, "fallback": used_fallback}


@app.post("/v1/light/summary")
def light_summary(req: SummaryRequest):
    t0 = time.monotonic()
    if len({i.stat for i in req.items}) != len(req.items):
        raise HTTPException(status_code=422, detail="같은 stat 이 두 번 들어옴")
    items = [(i.stat, i.value, _zone_or_422(i.stat, i.value, i.zone)) for i in req.items]
    rng = random.Random()
    s, used_fallback = _light_comment(
        "summary", lc.build_summary(items, rng), lambda t: lc.parse_summary(t, items),
        lc.build_summary_text(items, rng), t0)
    return {"light_summary": s, "fallback": used_fallback}


def _comment_light_six(req: LightRequest):
    """LLM_LIGHT_MODE=six — 6회 흐름을 서버 안에서 순서대로 (백엔드 단계별 구현 전 임시·연동 시험용)."""
    values = {k: None for k in CANDIDATE_KEYS} | req.available_stats()
    try:
        out = run_light6(values, generator, Flow6Settings(attempts=max(2, _light6_attempts())))
    except ContextOverflowError as e:
        raise HTTPException(status_code=413, detail="LLM 입력 길이 초과") from e
    for c in out.calls:
        logger.info("light6 %s#%d %.1fs finish=%s → %s", c.stage, c.attempt, c.sec, c.finish, c.result)
    hl = [(k, s) for k, s in out.highlights if s]
    if out.status != 200 or not hl:
        logger.warning("light6 실패 %.1fs: %s", out.elapsed, out.error or "문장 0개")
        raise HTTPException(status_code=502, detail="LLM 출력 형식 오류")
    from app.light6 import NAMES
    summary = out.summary or f"이번 전적에서 두드러진 지표는 {', '.join(NAMES[k] for k, _ in hl)}입니다."
    logger.info("light6 선택 %s (규칙 정답 %s) 문장 %d/3 기본=%s %.1fs", out.picks, out.expected, len(hl),
                "모델" if out.summary else "서버", out.elapsed)
    return LightResponse(light_summary=summary, highlights=[Highlight(stat=k, sentence=s) for k, s in hl])


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
