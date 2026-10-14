"""light-v4 실행 흐름 (A/B/C). 나누는 곳은 이 LLM 서버 /v1/comment/light 안이다.
백엔드·API·요청/응답 스키마는 그대로 ({light_summary, highlights[{stat, sentence}]}).

환경변수 (서버를 켜기 전에 지정)
  LLM_LIGHT_MODE       a(기본) | b | c | c2 | d | v3
                       a = 1회, b = 2회(선정 + 문장3·총평), d = 3회(선정 + 문장3 + 총평, 라이트뷰 분리 대화 내용의 중간안),
                       c = 5회 v1(문장 호출에 해석·전체 기준 포함), c2 = 5회 v2(해석·전체 기준 뺌),
                       v3 = 라벨 없는 light-v3-lines (비교 기준)
  LLM_LIGHT_ATTEMPTS   기본 2. 검사에 걸렸을 때만 같은 서버에서 다시 생성하는 최대 횟수 (헤비 LLM_HEAVY_ATTEMPTS 와 같은 방식)
                       A: 호출 전체, B: 선정·문장 호출 각각, C: 선정·지표별 문장 각각
  LLM_LIGHT_BUDGET_SEC 기본 14. 이 요청 안에서 쓸 시간 상한(초). 다음 호출이 이 안에 끝날 것 같을 때만 시작한다.
                       23-타임아웃기준정리 v0.3 4.2절: 백엔드의 light 호출 1회 타임아웃 15초 → 1초 여유.
                       25번(CUDA) 품질 측정에서는 크게(예: 999) 두고, 시간은 따로 본다.
  LLM_LIGHT_FALLBACK   off(기본) | on. on 이면 규칙은 통과했는데 문장만 틀린 하이라이트를 서버 기본 문장으로 채운다.
  LLM_LIGHT_RANGES     보통 범위 파일 (기본 config/light_ranges.json)

다음 호출 예상 시간 = 이 요청에서 같은 종류 호출에 걸린 최대 시간, 없으면 DEFAULT_EST (Vulkan 실측 기준 추정치).
"""
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Callable

from app.light_rules import RangeConfig, expected_picks, target_count
from app.light_v3 import (
    LIGHT_V4_DECODING,
    LightContext,
    LightV3Result,
    build_light_a,
    build_light_comment,
    build_light_select,
    build_light_summary,
    build_light_write,
    fallback_summary,
    process_comment,
    process_lines,
    process_summary,
    select_with_rules,
    template_sentence,
)
from app.schemas import LightRequest

logger = logging.getLogger("uvicorn.error")

MODES = ("a", "b", "c", "c2", "d")

# 호출 종류별 예상 시간(초). light 1회 최악 약 10초, heavy 1회 약 2.8초 (23번 v0.3 2절, Vulkan).
# 선정·총평은 측정 전 추정치 — 25번·11번 측정 후 고친다.
DEFAULT_EST = {"a": 10.0, "select": 3.0, "write": 9.0, "comment": 3.0, "summary": 3.5}


@dataclass
class LightSettings:
    mode: str = "a"
    attempts: int = 2
    budget_sec: float = 14.0
    fallback: bool = False
    ranges: RangeConfig | None = None

    @classmethod
    def from_env(cls) -> "LightSettings":
        mode = os.getenv("LLM_LIGHT_MODE", "a").strip().lower()
        if mode not in MODES:
            raise ValueError(f"LLM_LIGHT_MODE 는 {MODES} 중 하나여야 합니다: {mode}")
        return cls(mode=mode,
                   attempts=max(1, int(os.getenv("LLM_LIGHT_ATTEMPTS", "2"))),
                   budget_sec=float(os.getenv("LLM_LIGHT_BUDGET_SEC", "14")),
                   fallback=os.getenv("LLM_LIGHT_FALLBACK", "off").lower() in ("on", "1", "true"))


@dataclass
class CallLog:
    kind: str           # a | select | write | comment:<key> | summary
    attempt: int
    sec: float
    finish: str
    text: str
    result: str = ""    # 이 호출의 처리 결과 (로그·측정용)


@dataclass
class LightOutcome:
    mode: str
    summary: str
    highlights: list[tuple[str, str]]
    summary_fallback: bool
    picks: list[str]                 # 규칙을 통과한 최종 선택 (고른 순서)
    expected: list[str]              # 규칙 그대로의 답 (측정용)
    order: list[str]                 # 프롬프트 후보 순서 (두드러진 순서)
    labels: dict[str, str]
    calls: list[CallLog] = field(default_factory=list)
    attempts: list[dict] = field(default_factory=list)   # 시도별 선정 결과 (측정용)
    sentence_dropped: list[str] = field(default_factory=list)
    sentence_fallback: list[str] = field(default_factory=list)   # 서버 기본 문장으로 채운 키
    budget_stops: list[str] = field(default_factory=list)        # 시간 예산 때문에 시작하지 않은 호출
    elapsed: float = 0.0


class LightFailure(Exception):
    """쓸 수 있는 하이라이트가 0개 → 502 (백엔드 재요청)."""

    def __init__(self, msg: str, outcome: LightOutcome):
        super().__init__(msg)
        self.outcome = outcome


class _Runner:
    def __init__(self, req: LightRequest, generator, st: LightSettings, clock: Callable[[], float]):
        self.req, self.gen, self.st, self.clock = req, generator, st, clock
        self.t0 = clock()
        self.longest: dict[str, float] = {}
        self.calls: list[CallLog] = []
        self.budget_stops: list[str] = []

    def elapsed(self) -> float:
        return self.clock() - self.t0

    def can_start(self, kind: str, first: bool = False) -> bool:
        if first:
            return True
        base = kind.split(":")[0]
        est = self.longest.get(base, DEFAULT_EST[base])
        if self.elapsed() + est <= self.st.budget_sec:
            return True
        self.budget_stops.append(f"{kind} (경과 {self.elapsed():.1f}s + 예상 {est:.1f}s > {self.st.budget_sec:g}s)")
        return False

    def call(self, kind: str, attempt: int, messages) -> CallLog:
        base = kind.split(":")[0]
        t = self.clock()
        res = self.gen.generate_text(f"light:{base}", messages, LIGHT_V4_DECODING[base])
        sec = self.clock() - t
        self.longest[base] = max(self.longest.get(base, 0.0), sec)
        log = CallLog(kind, attempt, sec, res.finish_reason, res.text)
        self.calls.append(log)
        return log


def run_light(req: LightRequest, generator, st: LightSettings | None = None,
              clock: Callable[[], float] = time.perf_counter) -> LightOutcome:
    """A/B/C 중 하나로 light 응답을 만든다. 하이라이트 0개면 LightFailure.
    입력 길이 초과(ContextOverflowError)는 그대로 올려 보낸다 (main 에서 413)."""
    st = st or LightSettings.from_env()
    r = _Runner(req, generator, st, clock)
    if st.mode == "a":
        ctx = build_light_a(req, st.ranges)
    else:
        ctx = build_light_select(req, st.ranges)
    out = LightOutcome(st.mode, "", [], True, [], expected_picks(ctx.ranked), ctx.order,
                       {s.key: s.label for s in ctx.ranked})
    try:
        if not ctx.order:
            raise LightFailure("값이 있는 후보 없음", out)
        {"a": _run_a, "b": _run_b, "c": _run_c, "c2": _run_c, "d": _run_d}[st.mode](r, ctx, out)
    finally:
        out.calls, out.budget_stops, out.elapsed = r.calls, r.budget_stops, r.elapsed()
    return out


# ---------------- A: 1회 ----------------
def _score(res: LightV3Result) -> tuple[int, int]:
    return len(res.highlights), int(not res.summary_fallback)


def _run_a(r: _Runner, ctx: LightContext, out: LightOutcome) -> None:
    target = target_count(ctx.ranked)
    best: LightV3Result | None = None
    for attempt in range(1, r.st.attempts + 1):
        if not r.can_start("a", first=attempt == 1):
            break
        log = r.call("a", attempt, ctx.messages)
        res = process_lines(log.text, ctx, rule_check=True, ref_in_text=True, allow_empty=True)
        log.result = f"하이라이트 {len(res.highlights)}개" + (f", 뺀 것 {res.dropped}" if res.dropped else "")
        out.attempts.append({"attempt": attempt, "raw": res.raw_picks, "picks": res.picks,
                             "rule_dropped": res.rule_dropped, "ok": len(res.highlights)})
        out.sentence_dropped += res.sentence_dropped
        if res.highlights and (best is None or _score(res) > _score(best)):
            best = res
        if len(res.highlights) >= target and not res.summary_fallback:
            break
    if best is None:
        raise LightFailure("쓸 수 있는 하이라이트 없음", out)
    _finish(r, out, best.picks, dict(best.highlights), best.summary if not best.summary_fallback else None, ctx)


# ---------------- 선정 (B·C 공통) ----------------
def _select(r: _Runner, ctx: LightContext, out: LightOutcome) -> list[str]:
    target = target_count(ctx.ranked)
    best: list[str] = []
    for attempt in range(1, r.st.attempts + 1):
        if not r.can_start("select", first=attempt == 1):
            break
        log = r.call("select", attempt, ctx.messages)
        raw, accepted, dropped = select_with_rules(log.text, ctx)
        log.result = f"고름 {raw} → 통과 {accepted}" + (f", 뺀 것 {dropped}" if dropped else "")
        out.attempts.append({"attempt": attempt, "raw": raw, "picks": accepted, "rule_dropped": dropped,
                             "ok": len(accepted)})
        if len(accepted) > len(best):
            best = accepted
        if len(best) >= target:
            break
    if not best:
        raise LightFailure("선정 결과 없음", out)
    return best


# ---------------- B: 2회 ----------------
def _run_b(r: _Runner, ctx: LightContext, out: LightOutcome) -> None:
    picks = _select(r, ctx, out)
    messages = build_light_write(ctx, picks)
    best: LightV3Result | None = None
    for attempt in range(1, r.st.attempts + 1):
        if not r.can_start("write"):
            break
        log = r.call("write", attempt, messages)
        res = process_lines(log.text, ctx, rule_check=False, allowed=picks, ref_in_text=True, allow_empty=True)
        log.result = f"문장 {len(res.highlights)}개" + (f", 뺀 것 {res.dropped}" if res.dropped else "")
        out.sentence_dropped += res.sentence_dropped
        if not res.highlights:
            continue
        if best is None or _score(res) > _score(best):
            best = res
        if len(res.highlights) >= len(picks) and not res.summary_fallback:
            break
    hl = dict(best.highlights) if best else {}
    summary = best.summary if best and not best.summary_fallback else None
    _finish(r, out, picks, hl, summary, ctx)


# ---------------- C: 5회 ----------------
def _run_c(r: _Runner, ctx: LightContext, out: LightOutcome) -> None:
    picks = _select(r, ctx, out)
    hl: dict[str, str] = {}
    for key in picks:
        kind = f"comment:{key}"
        messages = build_light_comment(ctx, key, extras=r.st.mode == "c")
        for attempt in range(1, r.st.attempts + 1):
            if not r.can_start(kind):
                break
            log = r.call(kind, attempt, messages)
            sentence, why = process_comment(log.text, ctx, key)
            log.result = "통과" if sentence else why
            if sentence:
                hl[key] = sentence
                break
            out.sentence_dropped.append(f"{key}: {why}")
    _finish(r, out, picks, hl, _summary_call(r, ctx, picks, hl), ctx)


def _summary_call(r: _Runner, ctx: LightContext, picks: list[str], hl: dict[str, str]) -> str | None:
    done = [(k, hl[k]) for k in picks if k in hl]
    if not done or not r.can_start("summary"):
        return None
    log = r.call("summary", 1, build_light_summary(done))
    summary, why = process_summary(log.text, ctx)
    log.result = "통과" if summary else why
    return summary


# ---------------- D: 3회 (선정 + 문장 3개 한 번에 + 총평) ----------------
def _run_d(r: _Runner, ctx: LightContext, out: LightOutcome) -> None:
    picks = _select(r, ctx, out)
    messages = build_light_write(ctx, picks, with_summary=False)
    best: LightV3Result | None = None
    for attempt in range(1, r.st.attempts + 1):
        if not r.can_start("write"):
            break
        log = r.call("write", attempt, messages)
        res = process_lines(log.text, ctx, rule_check=False, allowed=picks, ref_in_text=True, allow_empty=True,
                            summary_required=False)
        log.result = f"문장 {len(res.highlights)}개" + (f", 뺀 것 {res.dropped}" if res.dropped else "")
        out.sentence_dropped += res.sentence_dropped
        if res.highlights and (best is None or len(res.highlights) > len(best.highlights)):
            best = res
        if best and len(best.highlights) >= len(picks):
            break
    hl = dict(best.highlights) if best else {}
    _finish(r, out, picks, hl, _summary_call(r, ctx, picks, hl), ctx)


def _finish(r: _Runner, out: LightOutcome, picks: list[str], hl: dict[str, str], summary: str | None,
            ctx: LightContext) -> None:
    """고른 순서대로 하이라이트를 모으고, 필요하면 서버 문장으로 채운다."""
    out.picks = picks
    if r.st.fallback:
        for k in picks:
            if k not in hl:
                hl[k] = template_sentence(k, ctx.available[k])
                out.sentence_fallback.append(k)
    out.highlights = [(k, hl[k]) for k in picks if k in hl]
    if not out.highlights:
        raise LightFailure("쓸 수 있는 하이라이트 없음", out)
    out.summary_fallback = summary is None
    out.summary = summary or fallback_summary([k for k, _ in out.highlights], ctx)
