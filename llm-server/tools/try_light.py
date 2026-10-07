"""Qwen 으로 light 요청을 직접 돌려 보는 실험 스크립트 (서버 없이).

서버와 같은 생성기(app/generator.py 의 LlamaGenerator)를 그대로 쓰므로,
여기서 나온 결과 = 서버에 같은 설정으로 올렸을 때의 결과다.
주의: 25번 PC는 CUDA 빌드라 속도는 배포(Vulkan)와 다르다. 시간으로 타임아웃을 판단하지 않는다.

실행 (llm-server 폴더, 가상환경 켠 상태):
  $env:LLM_MODEL_PATH = "C:\\경로\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.try_light --runs 5                                  # 기본(프롬프트만)
  python -m tools.try_light --runs 5 --json-mode schema --repeat-penalty 1.1
"""
import argparse
import json
import os
import time
from pathlib import Path

from app.generator import JSON_MODES, LlamaGenerator
from app.prompt import LIGHT_DECODING, PROMPT_VERSION, build_light_messages
from app.schemas import LightRequest
from app.validation import OutputFormatError, check_light_output, parse_light_output

SAMPLE = Path(__file__).resolve().parent.parent / "tests" / "samples" / "light_testuser.json"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=5, help="같은 요청을 몇 번 돌릴지")
    parser.add_argument("--sample", default=str(SAMPLE), help="입력 JSON 파일 경로")
    parser.add_argument("--json-mode", default="off", choices=JSON_MODES, help="출력 형식 강제 방식")
    parser.add_argument("--repeat-penalty", type=float, default=None, help="반복 억제 (예: 1.1)")
    args = parser.parse_args()

    model_path = os.environ.get("LLM_MODEL_PATH")
    if not model_path:
        raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요.")

    req = LightRequest.model_validate(json.loads(Path(args.sample).read_text(encoding="utf-8")))
    messages = build_light_messages(req)
    available = req.available_stats()

    t0 = time.perf_counter()
    gen = LlamaGenerator(model_path, json_mode=args.json_mode, repeat_penalty=args.repeat_penalty)
    print(
        f"모델 적재 {time.perf_counter() - t0:.1f}초 / 프롬프트 {PROMPT_VERSION} / 디코딩 {LIGHT_DECODING} "
        f"/ json_mode={args.json_mode} / repeat_penalty={args.repeat_penalty}"
    )

    passed = 0
    for i in range(1, args.runs + 1):
        t = time.perf_counter()
        result = gen.generate_light(req, messages)
        elapsed = time.perf_counter() - t
        try:
            resp = parse_light_output(result.text)
            issues = check_light_output(resp, available)
            verdict = "통과" if not issues else f"내용 문제 {issues}"
            if not issues:
                passed += 1
        except OutputFormatError as e:
            verdict = f"형식 오류: {e}"
        print(f"\n[{i}/{args.runs}] {elapsed:.1f}초 finish={result.finish_reason} -> {verdict}")
        print(result.text)

    print(f"\n형식·내용 규칙 통과: {passed}/{args.runs}  (문장 내용의 정확성은 사람이 따로 확인)")


if __name__ == "__main__":
    main()