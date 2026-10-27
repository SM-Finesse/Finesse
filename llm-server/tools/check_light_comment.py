"""켜져 있는 LLM 서버의 개별·기본 코멘트 엔드포인트 확인 (light-v5.2).

실행 (llm-server 폴더, 가상환경):
  python -m tools.check_light_comment                          # http://localhost:8081
  python -m tools.check_light_comment --url http://192.168.0.113:8081

묶음 1개(tests/samples/lc_sets.json 의 high_mix)로 개별 3회 + 기본 1회를 보내고 문장·fallback·시간을 보여 준다.
"""
import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

SETS = Path(__file__).resolve().parent.parent / "tests" / "samples" / "lc_sets.json"


def _post(url: str, body: dict) -> tuple[int, dict, float]:
    req = urllib.request.Request(url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8")), time.perf_counter() - t
    except urllib.error.HTTPError as e:
        return e.code, {"detail": e.read().decode("utf-8", "replace")}, time.perf_counter() - t


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8081")
    ap.add_argument("--set", default="high_mix")
    args = ap.parse_args()
    items = json.loads(SETS.read_text(encoding="utf-8"))["sets"][args.set]
    ok = 0
    for k, v, z in items:
        code, out, sec = _post(f"{args.url}/v1/light/sentence", {"stat": k, "value": v, "zone": z})
        ok += code == 200
        print(f"[개별] {code} ({sec:.1f}s) {k} {v:+g} {z} fallback={out.get('fallback')} | {out.get('sentence', out)}")
    body = {"items": [{"stat": k, "value": v, "zone": z} for k, v, z in items]}
    code, out, sec = _post(f"{args.url}/v1/light/summary", body)
    ok += code == 200
    print(f"[기본] {code} ({sec:.1f}s) fallback={out.get('fallback')} | {out.get('light_summary', out)}")
    print(f"light-v5 통과: {ok}/4 (200 응답 수)")


if __name__ == "__main__":
    main()
