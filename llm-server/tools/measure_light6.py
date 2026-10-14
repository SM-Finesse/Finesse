"""라이트 1회 호출(배포 중) vs 6회 호출(34번 문서) 비교 측정 — 서버 없이 같은 코드를 직접 부른다.

실행 (llm-server 폴더, 가상환경, PowerShell — 11번 PC 기준):
  $env:LLM_MODEL_PATH = "C:\\출력토큰예산\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  $env:GGML_VK_VISIBLE_DEVICES = "1"
  python -m tools.measure_light6 --runs 1 --mock                    # 모델 없이 흐름만 (몇 초)
  python -m tools.measure_light6 --runs 1 --repeat-penalty 1.1      # 실제 모델로 짧게 (2~3분)
  python -m tools.measure_light6 --runs 10 --repeat-penalty 1.1     # 본 측정: 샘플 6개 × 2방식 × 10회 (약 30분)

비교 대상
  one : 지금 11·13번에 배포된 라이트 (light-baseline-v2-trsum, 1회 호출로 JSON 전체)
  six : 34번 6회 호출 (app/light6_flow.py — 백엔드가 할 단계 연결·재요청을 그대로 재현)
  두 방식 모두 같은 기준으로 채점한다: 선정은 31번 2.2절 규칙(실제 Q1·Q3), 문장은 검증 ③(31번 1.2·1.3·1.4).

옵션
  --attempts 2   six 의 호출 하나당 시도 횟수(최초 포함). 백엔드 재요청 규칙이 정해지면 맞춘다.
  --judge both   (기본) 1단계 모델 판정을 그대로 호출해 정확도를 기록하고, 끝내 틀리면 서버가 계산한 판정으로
                 2·3단계를 계속한다 — 34번 운영 방식이 아니라 측정용 (10/14 11번 확인: 모델 판정 정답 23%).
                 llm = 34번 그대로(판정 실패면 그 요청 502), server = 판정 호출 없이 서버 판정만.
                 첫 시도 결과도 따로 집계하므로 재시도 효과를 같이 볼 수 있다.
  --modes one,six  --samples ...(기본 tests/samples/l6_*.json 6개)

결과 (커밋하지 않음 — .gitignore 의 결과_*)
  결과_light6_<시각>.txt   : 실행마다 단계별 원문·검사 결과 + 마지막 요약표
  결과_light6_<시각>.jsonl : 실행 1건 = 1줄

시간 열
  six 순차 = 한 서버가 6회를 차례로 (LLM_LIGHT_MODE=six 와 같음)
  six 2대·4대 = 3단계 4회를 서버 n대에 나눴을 때 (34번 4절) — 각 호출 시간을 그대로 써서 계산한 환산값
"""
import argparse
import json
import logging
import os
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

from app.light6 import LIGHT6_VERSION, check_comment, expected_selection, judge_one, load_quartiles
from app.light6_flow import Flow6Settings, run_light6
from app.prompt import LIGHT_DECODING, PROMPT_VERSION, build_light_messages
from app.schemas import CANDIDATE_KEYS, LightRequest
from app.validation import OutputFormatError, process_light_output

SAMPLES = Path(__file__).resolve().parent.parent / "tests" / "samples"


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    logging.basicConfig(level=logging.INFO, format="      [log] %(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=10)
    ap.add_argument("--modes", default="one,six")
    ap.add_argument("--samples", nargs="+", default=[str(p) for p in sorted(SAMPLES.glob("l6_*.json"))])
    ap.add_argument("--attempts", type=int, default=2)
    ap.add_argument("--judge", default="both", choices=("llm", "both", "server"),
                    help="llm: 34번 그대로 / both(기본): 모델 판정을 재고, 실패하면 서버 판정으로 2·3단계 계속 / server: 판정 호출 없음")
    ap.add_argument("--repeat-penalty", type=float, default=None)
    ap.add_argument("--mock", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    samples = []
    for s in args.samples:
        req = LightRequest.model_validate(json.loads(Path(s).read_text(encoding="utf-8")))
        samples.append((Path(s).stem.removeprefix("l6_"), req))
    t0 = time.perf_counter()
    if args.mock:
        from app.generator import MockGenerator
        gen = MockGenerator()
    else:
        from app.generator import LlamaGenerator
        if not os.environ.get("LLM_MODEL_PATH"):
            raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요 (또는 --mock).")
        gen = LlamaGenerator(os.environ["LLM_MODEL_PATH"], repeat_penalty=args.repeat_penalty)
    load_sec = time.perf_counter() - t0
    q = load_quartiles()

    stem = args.out or f"결과_light6_{time.strftime('%Y%m%d_%H%M')}"
    txt = open(f"{stem}.txt", "w", encoding="utf-8")
    jl = open(f"{stem}.jsonl", "w", encoding="utf-8")

    def say(line: str = "") -> None:
        print(line)
        txt.write(line + "\n")

    say(f"one={PROMPT_VERSION} {LIGHT_DECODING} / six={LIGHT6_VERSION} attempts={args.attempts} judge={args.judge} / 생성기 {gen.name} "
        f"(적재 {load_sec:.1f}s) / repeat_penalty={args.repeat_penalty} / runs={args.runs}")
    rows = defaultdict(list)
    for name, req in samples:
        values = {k: None for k in CANDIDATE_KEYS} | req.available_stats()
        truth = {k: judge_one(values[k], *q[k]) for k in CANDIDATE_KEYS}
        expected = expected_selection(truth)
        for i in range(1, args.runs + 1):
            for mode in modes:                       # 회차마다 번갈아 (시간대 차이가 한쪽에 몰리지 않게)
                rec = (_one(gen, req, values) if mode == "one"
                       else _six(gen, values, Flow6Settings(attempts=args.attempts, judge=args.judge)))
                rec.update(mode=mode, sample=name, run=i, expected=expected)
                rec["rule_set_ok"] = len(rec["picks"]) == len(expected) and set(rec["picks"]) == set(expected)
                rec["rule_order_ok"] = rec["picks"] == expected
                rows[(mode, name)].append(rec)
                jl.write(json.dumps(rec, ensure_ascii=False) + "\n")
                jl.flush()
                _print(say, rec)
    say("\n" + "=" * 110)
    _summary(say, rows, modes, [n for n, _ in samples])
    txt.close()
    jl.close()
    print(f"\n저장: {stem}.txt, {stem}.jsonl")


# ---------------- 1회 호출 (배포 중인 light-baseline-v2) ----------------
def _one(gen, req: LightRequest, values) -> dict:
    t = time.perf_counter()
    res = gen.generate_light(req, build_light_messages(req))
    sec = time.perf_counter() - t
    rec = {"status": 200, "error": "", "elapsed": round(sec, 2), "picks": [], "highlights": [], "summary": None,
           "sentence_ok": 0, "sentence_why": [], "summary_ok": False, "raw": res.text, "finish": res.finish_reason}
    try:
        resp, _issues, _changed = process_light_output(res.text, req.available_stats())
    except OutputFormatError as e:
        rec.update(status=502, error=str(e))
        return rec
    seen = []
    for h in resp.highlights:
        if h.stat in values and values[h.stat] is not None and h.stat not in seen and len(seen) < 3:
            seen.append(h.stat)
            why = check_comment(h.sentence, [(h.stat, values[h.stat])], single=True)
            rec["highlights"].append({"stat": h.stat, "sentence": h.sentence, "why": why})
            rec["sentence_ok"] += why is None
            if why:
                rec["sentence_why"].append(why)
    rec["picks"] = seen
    rec["summary"] = resp.light_summary
    rec["summary_ok"] = check_comment(resp.light_summary, [(k, values[k]) for k in seen], single=False) is None
    return rec


# ---------------- 6회 호출 ----------------
def _six(gen, values, st) -> dict:
    out = run_light6(values, gen, st)
    first_sentence = {}
    for c in out.calls:
        if c.stage.startswith("sentence:") and c.attempt == 1:
            first_sentence[c.stage.split(":")[1]] = c.result == "통과"
    return {
        "status": out.status, "error": out.error, "elapsed": round(out.elapsed, 2),
        "est2": round(out.est(2), 2), "est4": round(out.est(4), 2),
        "picks": out.picks, "select_first_raw": out.select_first_raw,
        "highlights": [{"stat": k, "sentence": s} for k, s in out.highlights],
        "summary": out.summary, "summary_ok": out.summary is not None,
        "sentence_ok": sum(1 for _, s in out.highlights if s),
        "sentence_first_ok": sum(first_sentence.values()),
        "sentence_why": [c.result for c in out.calls if c.stage.startswith("sentence") and c.result != "통과"],
        "summary_why": [c.result for c in out.calls if c.stage == "summary" and c.result != "통과"],
        "judge_ok": out.judge_ok, "judge_attempts": out.judge_attempts,
        "judge_first_items": out.judge_first_items,
        "stage_sec": {k: round(v, 2) for k, v in out.stage_sec.items()},
        "calls": [{"stage": c.stage, "attempt": c.attempt, "sec": round(c.sec, 2), "finish": c.finish,
                   "result": c.result, "text": c.text} for c in out.calls],
    }


def _print(say, r: dict) -> None:
    tm = f"{r['elapsed']:.1f}s" + (f" (2대 {r['est2']:.1f}s, 4대 {r['est4']:.1f}s)" if r["mode"] == "six" else "")
    say(f"\n[{r['mode']} {r['sample']} {r['run']}] {r['status']} {tm} 선택 {r['picks']} (정답 {r['expected']}) "
        f"규칙 {'O' if r['rule_set_ok'] else 'X'} 문장 {r['sentence_ok']}/3 요약 {'O' if r['summary_ok'] else 'X'}")
    if r["mode"] == "one":
        say("    (원문) " + r["raw"].replace("\n", " ")[:600])
        for h in r["highlights"]:
            say(f"    {h['stat']}: {h['sentence']}" + (f"   ✗ {h['why']}" if h["why"] else ""))
    else:
        for c in r["calls"]:
            say(f"    · {c['stage']}#{c['attempt']} {c['sec']:.1f}s {c['finish']} → {c['result']}")
            say("      (원문) " + c["text"].replace("\n", "\n             ")[:900])
        for h in r["highlights"]:
            say(f"    {h['stat']}: {h['sentence']}")
    say(f"    요약: {r['summary']}")
    if r["error"]:
        say(f"    오류: {r['error']}")


def _summary(say, rows, modes, samples) -> None:
    def pct(a, b):
        return f"{a}/{b}"

    say("방식 샘플       200     하이3   문장통과   요약통과  규칙집합  규칙순서   시간 중앙/최대 (six: 순차 | 2대 | 4대)")
    totals = defaultdict(Counter)
    times = defaultdict(lambda: defaultdict(list))
    for mode in modes:
        for name in samples:
            rs = rows[(mode, name)]
            n = len(rs)
            ok = sum(r["status"] == 200 for r in rs)
            h3 = sum(r["sentence_ok"] == 3 for r in rs)
            sent = sum(r["sentence_ok"] for r in rs)
            summ = sum(r["summary_ok"] for r in rs)
            rset = sum(r["rule_set_ok"] for r in rs)
            rord = sum(r["rule_order_ok"] for r in rs)
            for key, val in (("n", n), ("ok", ok), ("h3", h3), ("sent", sent), ("summ", summ), ("rset", rset),
                             ("rord", rord)):
                totals[mode][key] += val
            el = [r["elapsed"] for r in rs]
            times[mode]["seq"] += el
            line = (f"{mode:<4} {name:<10} {pct(ok, n):>7} {pct(h3, n):>7} {pct(sent, 3 * n):>9} {pct(summ, n):>8} "
                    f"{pct(rset, n):>8} {pct(rord, n):>8}   {statistics.median(el):5.1f}/{max(el):<5.1f}")
            if mode == "six":
                e2 = [r["est2"] for r in rs]
                e4 = [r["est4"] for r in rs]
                times[mode]["e2"] += e2
                times[mode]["e4"] += e4
                line += f" | {statistics.median(e2):5.1f}/{max(e2):<5.1f} | {statistics.median(e4):5.1f}/{max(e4):<5.1f}"
            say(line)
    say("\n합계")
    for mode in modes:
        t = totals[mode]
        n = t["n"]
        line = (f"  {mode}: 200 {t['ok']}/{n} · 하이라이트 3개 모두 통과 {t['h3']}/{n} · 문장 {t['sent']}/{3 * n} · "
                f"요약 {t['summ']}/{n} · 규칙 정답(집합) {t['rset']}/{n} · 순서까지 {t['rord']}/{n} · "
                f"시간 중앙 {statistics.median(times[mode]['seq']):.1f}s 최대 {max(times[mode]['seq']):.1f}s")
        if mode == "six":
            line += (f" (2대 환산 중앙 {statistics.median(times[mode]['e2']):.1f}s 최대 {max(times[mode]['e2']):.1f}s, "
                     f"4대 최대 {max(times[mode]['e4']):.1f}s)")
        say(line)

    if "six" in modes:
        rs = [r for name in samples for r in rows[("six", name)]]
        n = len(rs)
        items = [ok for r in rs for ok in r["judge_first_items"].values()]
        all_ok = sum(1 for r in rs if r["judge_first_items"] and all(r["judge_first_items"].values()))
        say("\n6회 호출 단계별 (첫 시도 기준 = 재요청이 없었다면)")
        say(f"  1 판정: 11개 모두 정답 {all_ok}/{n} · 지표별 정답 {sum(items)}/{len(items)} "
            f"({sum(items) / max(1, len(items)):.0%}) · 재요청 후 통과 {sum(r['judge_ok'] for r in rs)}/{n}"
            + ("  ※ 2·3단계는 판정 실패 시 서버 계산 판정으로 계속한 결과(측정용)" if any(not r['judge_ok'] for r in rs) else ""))
        per_key = defaultdict(list)
        for r in rs:
            for k, ok in r["judge_first_items"].items():
                per_key[k].append(ok)
        say("     지표별 첫 시도 정답률: " + ", ".join(f"{k.removeprefix('delta_')} {sum(v)}/{len(v)}"
                                           for k, v in per_key.items()))
        first_pick = Counter(r["select_first_raw"][0] if r["select_first_raw"] else "(없음)" for r in rs)
        say(f"  2 선정: 규칙 정답(집합) {sum(r['rule_set_ok'] for r in rs)}/{n} · 첫 번째로 고른 지표 {dict(first_pick.most_common(5))}")
        sent_first = sum(r["sentence_first_ok"] for r in rs)
        n_sent = sum(len(r["picks"]) for r in rs)
        say(f"  3 개별: 첫 시도 통과 {sent_first}/{n_sent} · 최종 통과 {sum(r['sentence_ok'] for r in rs)}/{n_sent} · "
            f"실패 이유 {dict(Counter(w.split(' (')[0].split(':')[0] for r in rs for w in r['sentence_why']).most_common(8))}")
        say(f"    기본: 최종 통과 {sum(r['summary_ok'] for r in rs)}/{n} · 실패 이유 "
            f"{dict(Counter(w.split(' (')[0].split(':')[0] for r in rs for w in r['summary_why']).most_common(6))}")
        stage_med = defaultdict(list)
        for r in rs:
            for k, v in r["stage_sec"].items():
                stage_med[k.split(":")[0]].append(v)
        say("  단계별 시간 중앙/최대 (재요청 포함): " + ", ".join(
            f"{k} {statistics.median(v):.1f}/{max(v):.1f}s" for k, v in stage_med.items()))
    if "one" in modes:
        rs = [r for name in samples for r in rows[("one", name)]]
        say(f"\n1회 호출 문장 실패 이유: "
            f"{dict(Counter(w.split(' (')[0].split(':')[0] for r in rs for w in r['sentence_why']).most_common(8))}")
    say("\n※ 검증 ③을 통과한 문장도 뜻이 맞는지는 원문을 보고 사람이 확인한다.")
    say("※ 시간은 측정한 PC 기준. 배포 판단은 Vulkan(11·13번) 값으로 한다.")


if __name__ == "__main__":
    main()
