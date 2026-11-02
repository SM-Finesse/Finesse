"""라이트뷰 개별·기본 코멘트 (light-v5) 측정 — 서버 없이 같은 코드를 직접 부른다.

실행 (llm-server 폴더, 가상환경, PowerShell — 25번 PC 기준):
  $env:LLM_MODEL_PATH = "C:\\출력토큰예산\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.measure_light_comment --runs 1 --mock      # 모델 없이 흐름만 (몇 초)
  python -m tools.measure_light_comment --runs 1             # 실제 모델로 짧게 (약 1~2분)
  python -m tools.measure_light_comment --runs 5             # 본 측정: 묶음 8개 × 4호출 × 5회

채점: 첫 시도 통과 / 재시도 포함 통과(--attempts, 기본 2 = 서버 기본값) / 대체 문장 사용.
상태별(high·low·mid±·zero·hold)로도 나눠 본다 — 31번 2.2절 '보통은 차이가 크지 않다'가 지켜지는지.

결과 (커밋하지 않음 — .gitignore 의 결과_*)
  결과_light5_<시각>.txt / .jsonl  (UTF-8, --out 으로 이름 지정)
"""
import argparse
import json
import os
import random
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

from app import light_comment as lc

SETS = Path(__file__).resolve().parent.parent / "tests" / "samples" / "lc_sets.json"


def _call(gen, stage, messages, parse, attempts):
    tries = []
    for _ in range(attempts):
        t = time.perf_counter()
        res = gen.generate_text(f"light5:{stage}", messages, lc.LIGHT_COMMENT_DECODING[stage])
        sec = time.perf_counter() - t
        s, why = parse(res.text)
        tries.append({"raw": res.text, "finish": res.finish_reason, "sec": round(sec, 2), "ok": s is not None,
                      "text": s, "why": why})
        if s:
            break
    return tries


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--attempts", type=int, default=2)
    ap.add_argument("--sets", default=str(SETS))
    ap.add_argument("--only", nargs="*", default=None, help="묶음 이름 일부만")
    ap.add_argument("--repeat-penalty", type=float, default=None)
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--mock", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    sets = json.loads(Path(args.sets).read_text(encoding="utf-8"))["sets"]
    if args.only:
        sets = {k: v for k, v in sets.items() if k in args.only}
    if args.mock:
        from app.generator import MockGenerator
        gen = MockGenerator()
    else:
        from app.generator import LlamaGenerator
        if not os.environ.get("LLM_MODEL_PATH"):
            raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요 (또는 --mock).")
        gen = LlamaGenerator(os.environ["LLM_MODEL_PATH"], repeat_penalty=args.repeat_penalty)
    rng = random.Random(args.seed)
    stem = args.out or f"결과_light5_{time.strftime('%Y%m%d_%H%M')}"
    stats = defaultdict(lambda: {"n": 0, "first": 0, "final": 0})
    secs = defaultdict(list)
    why_count = defaultdict(int)

    with open(f"{stem}.txt", "w", encoding="utf-8") as txt, open(f"{stem}.jsonl", "w", encoding="utf-8") as jl:
        def say(line: str = "") -> None:
            print(line)
            txt.write(line + "\n")
            txt.flush()

        say(f"{lc.LIGHT_COMMENT_VERSION} {lc.LIGHT_COMMENT_DECODING} / 생성기 {gen.name} / attempts={args.attempts} "
            f"/ repeat_penalty={args.repeat_penalty} / runs={args.runs}")
        for run in range(1, args.runs + 1):
            for name, raw_items in sets.items():
                items = [(k, float(v), z) for k, v, z in raw_items]
                say(f"\n=== [{run}회] {name}")
                calls = [("sentence", (k, v, z)) for k, v, z in items] + [("summary", None)]
                for stage, item in calls:
                    if stage == "sentence":
                        k, v, z = item
                        st = lc.state(k, v, z)
                        tries = _call(gen, stage, lc.build_sentence(k, v, z, rng),
                                      lambda t, k=k, v=v, z=z: lc.parse_sentence(t, k, v, z), args.attempts)
                        label = f"{k} {lc.fmt_value(k, v)} {z}→{st}"
                        groups = ("sentence", f"sentence:{st}")
                    else:
                        tries = _call(gen, stage, lc.build_summary(items, rng),
                                      lambda t, items=items: lc.parse_summary(t, items), args.attempts)
                        label = "기본 코멘트"
                        groups = ("summary", "summary:all_mid" if name == "all_mid" else "summary")
                    for g in dict.fromkeys(groups):
                        stats[g]["n"] += 1
                        stats[g]["first"] += tries[0]["ok"]
                        stats[g]["final"] += tries[-1]["ok"]
                    for t in tries:
                        secs[stage].append(t["sec"])
                        if not t["ok"]:
                            why_count[f"{stage}: {str(t['why']).split(':')[0]}"] += 1
                    say(f"  [{stage}] {label}")
                    for i, t in enumerate(tries, 1):
                        mark = "통과" if t["ok"] else f"실패({t['why']})"
                        say(f"    시도{i} {t['sec']:.2f}s {t['finish']} {mark}: {t['raw'].strip()}")
                    if not tries[-1]["ok"]:
                        say("    → 대체 문장 사용")
                    jl.write(json.dumps({"run": run, "set": name, "stage": stage, "item": item, "tries": tries},
                                        ensure_ascii=False) + "\n")
        say("\n" + "=" * 90)
        say(f"{'구분':<22}{'호출':>6}{'첫 시도 통과':>14}{'재시도 포함':>12}{'대체 문장':>10}")
        for g in sorted(stats):
            s = stats[g]
            say(f"{g:<22}{s['n']:>6}{s['first'] / s['n']:>14.0%}{s['final'] / s['n']:>12.0%}"
                f"{1 - s['final'] / s['n']:>10.0%}")
        for stage, xs in secs.items():
            say(f"시간 {stage}: 중앙 {statistics.median(xs):.2f}s / 최대 {max(xs):.2f}s (시도 {len(xs)}회)")
        say("실패 이유: " + ", ".join(f"{k} {v}" for k, v in sorted(why_count.items(), key=lambda x: -x[1])))
    print(f"\n저장: {stem}.txt, {stem}.jsonl")


if __name__ == "__main__":
    main()
