"""Qwen 베이스 모델로 light 요청을 직접 돌려 보는 실험 스크립트 (서버 없이).

목적: Mock 을 Qwen 으로 바꾸기 전에, 실제 모델이 우리 프롬프트로 어떤 답을 내는지
      눈으로 확인하고 형식 준수율을 잰다 (v1.2 12.1절 형식 준수율).
주의: 25번 PC는 CUDA 빌드라 속도는 배포(Vulkan)와 다르다. 여기서 잰 시간으로
      타임아웃을 판단하지 않는다.

실행 (llm-server 폴더, 가상환경 켠 상태):
  $env:LLM_MODEL_PATH = "C:\\경로\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.try_light --runs 5
"""
import argparse
import json
import os
import time
from pathlib import Path

from app.prompt import LIGHT_DECODING, PROMPT_VERSION, build_light_messages
from app.schemas import LightRequest
from app.validation import OutputFormatError, check_light_output, parse_light_output

SAMPLE = Path(__file__).resolve().parent.parent / "tests" / "samples" / "light_testuser.json"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=5, help="같은 요청을 몇 번 돌릴지")
    parser.add_argument("--sample", default=str(SAMPLE), help="입력 JSON 파일 경로")
    args = parser.parse_args()

    model_path = os.environ.get("LLM_MODEL_PATH")
    if not model_path:
        raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요.")

    from llama_cpp import Llama  # 무거운 라이브러리라 실제로 쓸 때만 불러온다

    req = LightRequest.model_validate(json.loads(Path(args.sample).read_text(encoding="utf-8")))
    messages = build_light_messages(req)
    available = req.available_stats()

    t0 = time.perf_counter()
    # n_gpu_layers=-1: 모든 층을 GPU에 올림 / n_ctx=2048: v1.2 4.1절 max_seq_length
    llm = Llama(model_path=model_path, n_gpu_layers=-1, n_ctx=2048, verbose=False)
    print(f"모델 적재 {time.perf_counter() - t0:.1f}초 / 프롬프트 {PROMPT_VERSION} / 디코딩 {LIGHT_DECODING}")

    passed = 0
    for i in range(1, args.runs + 1):
        t = time.perf_counter()
        out = llm.create_chat_completion(messages=messages, **LIGHT_DECODING)
        elapsed = time.perf_counter() - t

        choice = out["choices"][0]
        text = choice["message"]["content"] or ""
        finish = choice.get("finish_reason")
        usage = out.get("usage", {})

        try:
            resp = parse_light_output(text)
            issues = check_light_output(resp, available)
            verdict = "통과" if not issues else f"내용 문제 {issues}"
            if not issues:
                passed += 1
        except OutputFormatError as e:
            verdict = f"형식 오류: {e}"

        print(
            f"\n[{i}/{args.runs}] {elapsed:.1f}초 finish={finish} "
            f"프롬프트 {usage.get('prompt_tokens')}tok 생성 {usage.get('completion_tokens')}tok -> {verdict}"
        )
        print(text)

    print(f"\n형식·내용 모두 통과: {passed}/{args.runs}")


if __name__ == "__main__":
    main()