"""light A/B/C 비교 측정 (서버 없이, 서버와 같은 run_light 를 그대로 호출).

실행 (llm-server 폴더, 가상환경 켠 상태, PowerShell):
  $env:LLM_MODEL_PATH = "C:\\경로\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.measure_light --runs 2 --mock                 # 모델 없이 흐름만 확인 (몇 초)
  python -m tools.measure_light --runs 3                        # 실제 모델로 짧게 확인
  python -m tools.measure_light --runs 20 --repeat-penalty 1.1  # 본 측정 (기본: D·C v2 × 샘플 4개 × 20회)
  python -m tools.measure_light --runs 20 --modes a,b,c        # 10/14 A·B·C 비교를 다시 할 때

옵션
  --modes d,c2             비교할 방식 a | b | c | c2 | d (v3 은 tools/try_light.py --prompt v3 로 따로)
  --samples ...            입력 JSON (기본: light_testuser=neg, light_pos=pos, light_spread=규칙 검사용 합성)
  --attempts 2             LLM_LIGHT_ATTEMPTS 와 같음. 첫 시도 결과도 따로 집계하므로 한 번 돌리면 둘 다 나온다
  --budget 999             LLM_LIGHT_BUDGET_SEC. 25번(CUDA)은 배포(Vulkan)보다 느려서, 품질은 예산 없이 재고
                           시간은 '걸린 시간' 열로 따로 본다. 배포 조건 그대로 보려면 --budget 14
  --fallback               규칙 통과·문장 실패를 서버 기본 문장으로 채움 (기본 off: 모델 실력만 봄)
  --repeat-penalty 1.1     생성기 옵션 (try_light 와 같음)

결과 파일 (커밋하지 않음 — .gitignore 에 결과_* 확인)
  결과_light_비교_<시각>.txt   : 매 실행의 원문·처리 결과 + 마지막 요약표 (문장 정확성은 사람이 이 파일로 확인)
  결과_light_비교_<시각>.jsonl : 실행 1건 = 1줄 (보고서·논문 표 계산용)

요약표 열
  200        : 하이라이트 1개 이상 (백엔드가 받는 응답)
  3개        : 하이라이트 3개
  첫시도3    : 재시도 없이 첫 시도(A: 전체, B·C: 선정)가 규칙을 통과한 3개
  규칙집합   : 최종 선택이 규칙 정답과 같은 3개 (순서 무관)
  규칙순서   : 순서까지 같음 (규칙 5)
  형식실패   : 모델 출력에서 후보 번호를 하나도 읽지 못한 시도 수 (예: "번호, 번호, 번호")
  선정위반   : 사후 검사로 뺀 선택 줄 수 (전체 시도 합)
  문장오류   : 문장 검사로 뺀 줄 수 (이유별은 아래)
  서버문장 / 서버총평 : 대체 문장 수 / 서버가 만든 총평 횟수
  예문같음   : (C) 예문(기본 문장)을 그대로 쓴 하이라이트 수
  시간       : 요청 1건 중앙값 / 최대 (초), 호출 수 평균
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

from app.generator import ContextOverflowError
from app.light_pipeline import LightFailure, LightOutcome, LightSettings, run_light
from app.light_rules import load_ranges
from app.light_v3 import LIGHT_V4_DECODING, LIGHT_V4_VERSION, template_sentence
from app.schemas import LightRequest

SAMPLES = Path(__file__).resolve().parent.parent / "tests" / "samples"
DEFAULT_SAMPLES = [SAMPLES / "light_testuser.json", SAMPLES / "light_pos.json", SAMPLES / "light_spread.json",
                   SAMPLES / "light_group.json"]
NICK = {"light_testuser": "neg", "light_pos": "pos", "light_spread": "spread", "light_group": "group"}

# 사전 기준 (10/14, 측정 전에 정함). 문장 정확도는 사람이 판정하므로 여기서는 시간·형식만 자동 판정한다.
CRITERIA = {"max_sec": 14.0,      # 23번 v0.3 light 호출 1회 15초 - 1초 여유
            "ok_rate": 0.95}      # 200 응답 비율


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    # 생성기 로그(호출별 시간·prompt 토큰 수·gen 토큰 수)를 화면에 같이 보여 준다 → n_ctx 2048 여유 확인
    logging.basicConfig(level=logging.INFO, format="      [log] %(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--modes", default="d,c2")
    ap.add_argument("--samples", nargs="+", default=[str(p) for p in DEFAULT_SAMPLES])
    ap.add_argument("--attempts", type=int, default=2)
    ap.add_argument("--budget", type=float, default=999.0)
    ap.add_argument("--fallback", action="store_true")
    ap.add_argument("--repeat-penalty", type=float, default=None)
    ap.add_argument("--mock", action="store_true", help="모델 없이 Mock 으로 흐름만 확인")
    ap.add_argument("--out", default=None, help="결과 파일 이름 앞부분 (기본 결과_light_비교_<시각>)")
    args = ap.parse_args()

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    samples = [(NICK.get(Path(s).stem, Path(s).stem),
                LightRequest.model_validate(json.loads(Path(s).read_text(encoding="utf-8")))) for s in args.samples]
    t0 = time.perf_counter()
    if args.mock:
        from app.generator import MockGenerator
        gen = MockGenerator()
    else:
        from app.generator import LlamaGenerator
        model_path = os.environ.get("LLM_MODEL_PATH")
        if not model_path:
            raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요 (또는 --mock).")
        gen = LlamaGenerator(model_path, repeat_penalty=args.repeat_penalty)
    load_sec = time.perf_counter() - t0

    stem = args.out or f"결과_light_비교_{time.strftime('%Y%m%d_%H%M')}"
    txt = open(f"{stem}.txt", "w", encoding="utf-8")  # noqa: SIM115
    jl = open(f"{stem}.jsonl", "w", encoding="utf-8")  # noqa: SIM115

    def say(line: str = "") -> None:
        print(line)
        txt.write(line + "\n")

    cfg = load_ranges()
    say(f"{LIGHT_V4_VERSION} / 생성기 {gen.name} (적재 {load_sec:.1f}s) / 범위 {cfg.source} / modes={modes} "
        f"/ runs={args.runs} / attempts={args.attempts} / budget={args.budget:g}s / fallback={args.fallback} "
        f"/ repeat_penalty={args.repeat_penalty}")
    say(f"디코딩 {LIGHT_V4_DECODING}")
    rows = defaultdict(list)
    # 시간에 따른 차이(발열 등)가 한 방식에 몰리지 않게 회차마다 방식을 번갈아 돈다
    for name, req in samples:
        for i in range(1, args.runs + 1):
            for mode in modes:
                st = LightSettings(mode=mode, attempts=args.attempts, budget_sec=args.budget, fallback=args.fallback)
                rec = _one(gen, req, st, name, i)
                rows[(mode, name)].append(rec)
                jl.write(json.dumps(rec, ensure_ascii=False) + "\n")
                jl.flush()
                _print_run(say, rec)
    say("\n" + "=" * 100)
    _summary(say, rows, modes, [n for n, _ in samples])
    txt.close()
    jl.close()
    print(f"\n저장: {stem}.txt, {stem}.jsonl")


def _one(gen, req, st, sample, i) -> dict:
    status, err = 200, ""
    try:
        out = run_light(req, gen, st)
    except LightFailure as e:
        out, status, err = e.outcome, 502, str(e)
    except ContextOverflowError as e:   # 서버라면 413 (입력 길이 초과)
        out = LightOutcome(st.mode, "", [], True, [], [], [], {})
        status, err = 413, str(e)
    first = out.attempts[0] if out.attempts else {"picks": [], "ok": 0}
    target = min(3, len(out.order))
    copied = [k for k, s in out.highlights if s == template_sentence(k, req.available_stats()[k])
              and k not in out.sentence_fallback]
    return {
        "mode": st.mode, "sample": sample, "run": i, "status": status, "error": err,
        "elapsed": round(out.elapsed, 2), "n_calls": len(out.calls),
        "picks": out.picks, "expected": out.expected, "labels": out.labels,
        "highlights": [{"stat": k, "sentence": s} for k, s in out.highlights],
        "summary": out.summary, "summary_fallback": status == 200 and out.summary_fallback,
        "first_attempt_full": status == 200 and len(first["picks"]) >= target,
        "first_attempt_raw": first.get("raw", []),
        "rule_set_ok": status == 200 and len(out.picks) == target and set(out.picks) == set(out.expected),
        "rule_order_ok": status == 200 and out.picks == out.expected,
        "rule_dropped": [d for a in out.attempts for d in a.get("rule_dropped", [])],
        "format_fail": sum(1 for a in out.attempts if not a.get("raw")),   # 번호를 하나도 못 읽은 시도
        "sentence_dropped": out.sentence_dropped, "sentence_fallback": out.sentence_fallback,
        "example_copied": copied, "budget_stops": out.budget_stops,
        "calls": [{"kind": c.kind, "attempt": c.attempt, "sec": round(c.sec, 2), "finish": c.finish,
                   "result": c.result, "text": c.text} for c in out.calls],
    }


def _print_run(say, r: dict) -> None:
    head = (f"\n[{r['mode'].upper()} {r['sample']} {r['run']}] {r['status']} {r['elapsed']:.1f}s 호출 {r['n_calls']} "
            f"선택 {r['picks']} (정답 {r['expected']}) 규칙 {'O' if r['rule_set_ok'] else 'X'}")
    say(head)
    for c in r["calls"]:
        say(f"    · {c['kind']}#{c['attempt']} {c['sec']:.1f}s {c['finish']} → {c['result']}")
        say("      (원문) " + c["text"].replace("\n", "\n             "))
    for h in r["highlights"]:
        say(f"    {h['stat']}: {h['sentence']}")
    say(f"    총평{'(서버)' if r['summary_fallback'] else ''}: {r['summary']}")
    if r["error"]:
        say(f"    오류: {r['error']}")


def _summary(say, rows, modes, samples) -> None:
    head = ("방식 샘플    200    3개   첫시도3 규칙집합 규칙순서 형식실패 선정위반 문장오류 서버문장 서버총평 예문같음 "
            "시간중앙/최대  호출")
    say(head)
    for name in samples:
        for mode in modes:
            rs = rows[(mode, name)]
            n = len(rs)
            t = [r["elapsed"] for r in rs]
            frac = lambda c, n=n: f"{c}/{n}"
            say(f"{mode.upper():<4} {name:<7} {frac(sum(r['status'] == 200 for r in rs)):>6} "
                f"{frac(sum(len(r['highlights']) == 3 for r in rs)):>6} "
                f"{frac(sum(r['first_attempt_full'] for r in rs)):>7} "
                f"{frac(sum(r['rule_set_ok'] for r in rs)):>8} {frac(sum(r['rule_order_ok'] for r in rs)):>8} "
                f"{sum(r['format_fail'] for r in rs):>8} "
                f"{sum(len(r['rule_dropped']) for r in rs):>8} {sum(len(r['sentence_dropped']) for r in rs):>8} "
                f"{sum(len(r['sentence_fallback']) for r in rs):>8} {sum(r['summary_fallback'] for r in rs):>8} "
                f"{sum(len(r['example_copied']) for r in rs):>8} "
                f"{statistics.median(t):>6.1f}/{max(t):<6.1f} {statistics.mean(r['n_calls'] for r in rs):>5.1f}")
    say("\n문장 오류 이유 (방식별)")
    for mode in modes:
        why = Counter(d.split(": ", 1)[1].split(" (")[0].split(":")[0]
                      for name in samples for r in rows[(mode, name)] for d in r["sentence_dropped"])
        say(f"  {mode.upper()}: {dict(why.most_common())}")
    say("\n첫 시도에서 모델이 맨 처음 고른 지표 (쏠림 확인)")
    for name in samples:
        for mode in modes:
            first = Counter(r["first_attempt_raw"][0] if r["first_attempt_raw"] else "(없음)" for r in rows[(mode, name)])
            say(f"  {mode.upper()} {name}: {dict(first.most_common())}")
    say(f"\n사전 기준 판정 (시간 최대 ≤ {CRITERIA['max_sec']:g}초, 200 비율 ≥ {CRITERIA['ok_rate']:.0%}; 문장 정확도는 사람 판정)")
    for mode in modes:
        rs = [r for name in samples for r in rows[(mode, name)]]
        worst = max(r["elapsed"] for r in rs)
        ok = sum(r["status"] == 200 for r in rs) / len(rs)
        verdict = "통과" if worst <= CRITERIA["max_sec"] and ok >= CRITERIA["ok_rate"] else "미달"
        say(f"  {mode.upper()}: 최대 {worst:.1f}초, 200 {ok:.0%} → {verdict}")
    if "group" in samples:
        say("\n그룹 함정(group) 샘플: 규칙 정답 = [1] [3] [4] (정렬된 앞 3개가 아님). 모델이 [1] [2] [3] 을 고르면 [2] 는 같은 그룹이라 빠진다.")
        for mode in modes:
            rs = rows[(mode, "group")]
            first3 = sum(1 for r in rs if len(r["first_attempt_raw"]) >= 2 and r["first_attempt_raw"][1] == "delta_app")
            say(f"  {mode.upper()}: 첫 시도에서 [2](같은 그룹)를 고름 {first3}/{len(rs)}, 최종 규칙 정답 {sum(r['rule_set_ok'] for r in rs)}/{len(rs)}")
    say("\n※ 문장 내용의 정확성(방향은 맞지만 뜻이 틀린 문장 등)은 위 원문을 보고 사람이 확인한다.")
    say("※ 시간은 측정한 PC 기준. 배포 판단은 Vulkan(11·13번) 값으로 한다 (23번 v0.3 2절).")


if __name__ == "__main__":
    main()
