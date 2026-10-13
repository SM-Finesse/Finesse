"""Qwen 으로 light 요청을 직접 돌려 보는 실험 스크립트 (서버 없이).

서버와 같은 생성기(app/generator.py 의 LlamaGenerator)를 그대로 쓰므로,
여기서 나온 결과 = 서버에 같은 설정으로 올렸을 때의 결과다.
주의: 25번 PC는 CUDA 빌드라 속도는 배포(Vulkan)와 다르다. 시간으로 타임아웃을 판단하지 않는다.

실행 (llm-server 폴더, 가상환경 켠 상태):
  $env:LLM_MODEL_PATH = "C:\\경로\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.try_light --runs 5                                  # 기본(프롬프트만)
  python -m tools.try_light --runs 5 --json-mode schema --repeat-penalty 1.1
  python -m tools.try_light --runs 20 --repeat-penalty 1.1 --long-trend 300   # tr_trend 300개짜리

같은 출력을 두 기준으로 채점한다.
  기존 기준: 출력 전체가 정확히 JSON 하나 + 내용 규칙 통과 (10/7까지의 서버)
  새 기준  : 서버의 현재 처리(process_light_output) 결과 200 + 내용 규칙 통과
"""
import argparse
import json
import os
import time
from pathlib import Path

from app.generator import JSON_MODES, LlamaGenerator
from app.prompt import LIGHT_DECODING, PROMPT_VERSION, build_light_messages
from app.schemas import LightRequest
from app.validation import (
    OutputFormatError,
    check_light_output,
    parse_light_output,
    process_light_output,
)

SAMPLE = Path(__file__).resolve().parent.parent / "tests" / "samples" / "light_testuser.json"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=5, help="같은 요청을 몇 번 돌릴지")
    parser.add_argument("--sample", default=str(SAMPLE), help="입력 JSON 파일 경로")
    parser.add_argument("--json-mode", default="off", choices=JSON_MODES, help="출력 형식 강제 방식")
    parser.add_argument("--repeat-penalty", type=float, default=None, help="반복 억제 (예: 1.1)")
    parser.add_argument("--long-trend", type=int, default=0, help="tr_trend 를 이 개수의 값으로 바꿔서 시험")
    args = parser.parse_args()

    model_path = os.environ.get("LLM_MODEL_PATH")
    if not model_path:
        raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요.")

    data = json.loads(Path(args.sample).read_text(encoding="utf-8"))
    if args.long_trend:
        data["fixed_metrics"]["tr_trend"] = [20000 + i * 3 for i in range(args.long_trend)]
    req = LightRequest.model_validate(data)
    messages = build_light_messages(req)
    available = req.available_stats()

    t0 = time.perf_counter()
    gen = LlamaGenerator(model_path, json_mode=args.json_mode, repeat_penalty=args.repeat_penalty)
    print(
        f"모델 적재 {time.perf_counter() - t0:.1f}초 / 프롬프트 {PROMPT_VERSION} / 디코딩 {LIGHT_DECODING} "
        f"/ json_mode={args.json_mode} / repeat_penalty={args.repeat_penalty}"
    )

    old_pass = new_pass = 0
    for i in range(1, args.runs + 1):
        t = time.perf_counter()
        result = gen.generate_light(req, messages)
        elapsed = time.perf_counter() - t
        # 기존 기준
        try:
            old_ok = not check_light_output(parse_light_output(result.text, lenient=False), available)
        except OutputFormatError:
            old_ok = False
        # 새 기준 (서버와 같은 처리)
        try:
            _resp, issues, changed = process_light_output(result.text, available)
            verdict = "통과" if not issues else f"내용 문제 {issues}"
            if changed:
                verdict += f" (키 정리 {changed})"
            new_ok = not issues
        except OutputFormatError as e:
            verdict, new_ok = f"502 {e}", False
        old_pass += old_ok
        new_pass += new_ok
        print(f"\n[{i}/{args.runs}] {elapsed:.1f}초 finish={result.finish_reason} "
              f"기존={'O' if old_ok else 'X'} 새={'O' if new_ok else 'X'} -> {verdict}")
        print(result.text)

    print(f"\n통과 — 기존 기준 {old_pass}/{args.runs}, 새 기준 {new_pass}/{args.runs}"
          "  (문장 내용의 정확성은 사람이 따로 확인)")

if __name__ == "__main__":
    main()
