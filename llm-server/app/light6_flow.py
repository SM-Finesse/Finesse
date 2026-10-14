"""라이트 6회 호출 흐름 — 백엔드가 할 일(34번 2·5절)을 서버 안에서 그대로 재현한 참고 구현.

쓰는 곳
  1. tools/measure_light6.py — 백엔드 구현 전에 6회 호출의 품질·시간을 잰다.
  2. /v1/comment/light 에서 LLM_LIGHT_MODE=six — 백엔드가 단계별 엔드포인트로 바꾸기 전 임시 운영·연동 시험용.
     이때는 한 서버가 6회를 순서대로 하므로(3단계 동시 처리 없음) 백엔드가 서버 2대로 나눌 때보다 느리다.

백엔드 구현과 맞춰야 하는 규칙 (34번 5절)
  - 단계·호출 단위로 재요청한다. 문장 하나가 틀려도 선정부터 다시 하지 않는다.
  - ① 판정 검증 실패 → 판정만 재요청. 백엔드 값으로 덮어쓰지 않는다(선정 주체 원칙).
  - ② 선정: 3개 초과 → 앞 3개 / 미만 → 재요청 / 끝내 실패 → 하이라이트 없이 응답.
  - ③ 코멘트: 실패한 호출만 재요청 / 끝내 실패 → 개별은 카드에 수치만(문장 없음), 기본은 생략.
  - 3단계 4회는 서로 독립 — 동시에 보낼 수 있다. 시간 환산(est_2·est_4)은 이 독립성으로 계산한다.
"""
import time
from dataclasses import dataclass, field
from typing import Callable

from app.light6 import (
    LIGHT6_DECODING,
    build_judge,
    build_select,
    build_sentence,
    build_summary,
    expected_selection,
    judge_one,
    load_quartiles,
    parse_judge,
    parse_select,
    parse_sentence,
    parse_summary,
)
from app.schemas import CANDIDATE_KEYS


@dataclass
class Flow6Settings:
    attempts: int = 2        # 호출 하나당 시도 횟수(최초 포함). 백엔드 재요청 규칙이 정해지면 맞춘다(34번 6절 4번).
    # 판정을 누가 다음 단계로 넘기나 — llm: 34번 그대로(검증 통과한 모델 판정만) /
    # both: [측정용] 모델 판정은 그대로 호출·기록하되, 끝내 실패하면 서버 계산 판정으로 2·3단계를 계속
    #       (10/14 11번: 1.5B 모델 판정 지표별 정답 23%, 11개 모두 정답 0/6 → 2·3단계를 측정하려고 둠) /
    # server: [측정용] 판정 호출 없이 서버 계산 판정만
    judge: str = "llm"


@dataclass
class Call6:
    stage: str               # judge | select | sentence:<stat> | summary
    attempt: int
    sec: float
    finish: str
    text: str
    result: str = ""


@dataclass
class Flow6Outcome:
    status: int = 200
    error: str = ""
    highlights: list[tuple[str, str | None]] = field(default_factory=list)   # 문장 없음(None) = 카드에 수치만
    summary: str | None = None                                             # None = 기본 코멘트 생략
    judge_ok: bool = False
    judge_first_items: dict[str, bool] = field(default_factory=dict)       # 첫 시도 지표별 정답 여부
    judge_attempts: int = 0
    select_first_raw: list[str] = field(default_factory=list)
    picks: list[str] = field(default_factory=list)
    expected: list[str] = field(default_factory=list)
    calls: list[Call6] = field(default_factory=list)
    stage_sec: dict[str, float] = field(default_factory=dict)              # 단계(또는 3단계 호출)별 걸린 시간 합
    elapsed: float = 0.0

    def est(self, servers: int) -> float:
        """서버 n대일 때 걸릴 시간: 1·2단계는 순차, 3단계 4개 작업은 n대에 나눔(긴 것부터 비어 있는 서버로)."""
        jobs = sorted((v for k, v in self.stage_sec.items() if k.startswith(("sentence", "summary"))), reverse=True)
        lanes = [0.0] * max(1, servers)
        for j in jobs:
            lanes[lanes.index(min(lanes))] += j
        return self.stage_sec.get("judge", 0.0) + self.stage_sec.get("select", 0.0) + (max(lanes) if jobs else 0.0)


def run_light6(values: dict[str, float | None], generator, st: Flow6Settings | None = None,
               quartiles: dict[str, tuple[float, float]] | None = None,
               clock: Callable[[], float] = time.perf_counter) -> Flow6Outcome:
    st = st or Flow6Settings()
    q = quartiles or load_quartiles()
    out = Flow6Outcome()
    t0 = clock()
    cands = [(k, values.get(k), q[k][0], q[k][1]) for k in CANDIDATE_KEYS]
    truth = {k: judge_one(v, q1, q3) for k, v, q1, q3 in cands}
    out.expected = expected_selection(truth)

    def call(stage: str, attempt: int, messages) -> Call6:
        base = stage.split(":")[0]
        t = clock()
        res = generator.generate_text(f"light6:{base}", messages, LIGHT6_DECODING[base])
        c = Call6(stage, attempt, clock() - t, res.finish_reason, res.text)
        out.calls.append(c)
        out.stage_sec[stage] = out.stage_sec.get(stage, 0.0) + c.sec
        return c

    try:
        # ---------- 1단계 판정 ----------
        judged = None
        for attempt in range(1, (st.attempts if st.judge != "server" else 0) + 1):
            c = call("judge", attempt, build_judge(cands))
            r = parse_judge(c.text, cands)
            out.judge_attempts = attempt
            if attempt == 1:
                out.judge_first_items = r.item_ok
            c.result = "통과" if not r.errors else f"불일치 {len(r.errors)}개: {r.errors[:4]}"
            if not r.errors:
                judged = r.judgments
                break
        out.judge_ok = judged is not None
        if judged is None:
            if st.judge == "llm":
                out.status, out.error = 502, "판정 검증 실패"
                return out
            judged = truth                      # [측정용] 서버 계산 판정으로 계속 (34번 운영 방식 아님)

        # ---------- 2단계 선정 (모델 판정 그대로 넘김 — 검증을 통과했으므로 정답과 ±0.1 안) ----------
        jlist = [(k, *judged[k]) for k in CANDIDATE_KEYS]
        target = min(3, sum(1 for _, z, _ in jlist if z != "없음"))
        picks: list[str] = []
        for attempt in range(1, st.attempts + 1):
            c = call("select", attempt, build_select(jlist))
            r = parse_select(c.text, jlist)
            if attempt == 1:
                out.select_first_raw = r.raw
            c.result = f"고름 {r.raw} → 통과 {r.stats}" + (f", 뺀 것 {r.dropped}" if r.dropped else "")
            if len(r.stats) >= target:
                picks = r.stats[:3]
                break
        if not picks:
            out.status, out.error = 502, "선정 실패 — 하이라이트 없이 응답"
            return out
        out.picks = picks

        # ---------- 3단계 개별 ×3 · 기본 ×1 (서로 독립) ----------
        for k in picks:
            sentence = None
            for attempt in range(1, st.attempts + 1):
                c = call(f"sentence:{k}", attempt, build_sentence(k, values[k]))
                sentence, why = parse_sentence(c.text, k, values[k])
                c.result = "통과" if sentence else why
                if sentence:
                    break
            out.highlights.append((k, sentence))
        items = [(k, values[k]) for k in picks]
        for attempt in range(1, st.attempts + 1):
            c = call("summary", attempt, build_summary(items))
            summary, why = parse_summary(c.text, items)
            c.result = "통과" if summary else why
            if summary:
                out.summary = summary
                break
        return out
    finally:
        out.elapsed = clock() - t0
