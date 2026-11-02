"""Qwen 으로 heavy 챕터를 직접 돌려 보는 실험 스크립트 (서버 없이).

서버와 같은 생성기·프롬프트·응답 검사를 쓰므로, 여기서 나온 결과 = 서버에 같은 설정으로 올렸을 때의 결과다.
주의: 25번 PC는 CUDA 빌드라 속도는 배포(Vulkan)와 다르다. 시간으로 타임아웃을 판단하지 않는다.

실행 (llm-server 폴더, 가상환경 켠 상태):
  $env:LLM_MODEL_PATH = "C:\\경로\\qwen2.5-1.5b-instruct-q4_k_m.gguf"
  python -m tools.try_heavy --runs 10 --repeat-penalty 1.1                 # 8챕터 × 10회
  python -m tools.try_heavy --runs 10 --repeat-penalty 1.1 --chapter comeback_rate
  python -m tools.try_heavy --runs 10 --repeat-penalty 1.1 --out 결과_heavy.txt      # 화면 + UTF-8 파일

※ PowerShell 에서 "| Out-File" 로 저장하면 한글이 cp949 로 한 번 잘못 읽혀 깨진다(10/16). 파일은 --out 으로 저장.

마지막에 챕터별 표: 통과 / 502(서버라면 형식 오류) / 잘림(finish=length) / 평균·최대 생성 토큰.
서버와 같은 처리(process_heavy_output)로 채점한다: 문장 다듬기 → 베낌·중국어·키 이름 검사.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

from app.generator import JSON_MODES, LlamaGenerator
from app.prompt import HEAVY_DECODING, HEAVY_PROMPT_VERSION, build_heavy_messages
from app.schemas import HEAVY_CHAPTER_IDS, HeavyRequest
from app.validation import OutputFormatError, process_heavy_output

SAMPLE = Path(__file__).resolve().parent.parent / "tests" / "samples" / "heavy_chapters.json"

class _Tee:
    """화면과 파일에 같이 쓴다."""

    def __init__(self, *streams):
        self.streams = streams

    def write(self, text):
        for st in self.streams:
            st.write(text)
            st.flush()

    def flush(self):
        for st in self.streams:
            st.flush()


def main() -> None:
    # Windows 콘솔 기본 인코딩(cp949)은 중국어 등을 못 찍어 멈춘다 → UTF-8 로 출력
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=10, help="챕터마다 몇 번 돌릴지")
    parser.add_argument("--chapter", choices=HEAVY_CHAPTER_IDS, help="이 챕터만")
    parser.add_argument("--sample", default=str(SAMPLE), help="챕터별 data JSON 파일")
    parser.add_argument("--json-mode", default="off", choices=JSON_MODES)
    parser.add_argument("--repeat-penalty", type=float, default=None)
    parser.add_argument("--quiet", action="store_true", help="출력 원문은 실패한 것만 보여 줌")
    parser.add_argument("--out", help="결과를 이 파일에도 UTF-8 로 저장 (화면에도 그대로 보임)")
    args = parser.parse_args()
    if args.out:
        # 파일은 프로그램이 끝날 때까지 열어 둔다 (화면과 같이 쓰기)
        sys.stdout = _Tee(sys.stdout, open(args.out, "w", encoding="utf-8"))  # noqa: SIM115

    model_path = os.environ.get("LLM_MODEL_PATH")
    if not model_path:
        raise SystemExit("LLM_MODEL_PATH 환경변수에 GGUF 파일 경로를 지정하세요.")

    data = {k: v for k, v in json.loads(Path(args.sample).read_text(encoding="utf-8")).items() if not k.startswith("_")}
    chapters = [args.chapter] if args.chapter else list(HEAVY_CHAPTER_IDS)

    gen = LlamaGenerator(model_path, json_mode=args.json_mode, repeat_penalty=args.repeat_penalty)
    print(f"프롬프트 {HEAVY_PROMPT_VERSION} / 디코딩 {HEAVY_DECODING} / json_mode={args.json_mode} "
          f"/ repeat_penalty={args.repeat_penalty}")

    table = {}
    for ch in chapters:
        req = HeavyRequest.model_validate({"chapter_id": ch, "data": data[ch]})
        s = {"pass": 0, "fail": 0, "length": 0, "tokens": []}
        for i in range(1, args.runs + 1):
            t = time.perf_counter()
            messages = build_heavy_messages(req)  # v4: 비교 표현을 매번 새로 고름 (서버와 같음)
            result = gen.generate_heavy(req, messages)
            elapsed = time.perf_counter() - t
            if result.finish_reason == "length":
                s["length"] += 1
            s["tokens"].append(len(gen._llm.tokenize(result.text.encode("utf-8"), add_bos=False)))
            try:
                resp = process_heavy_output(result.text, ch, req.data)
                s["pass"] += 1
                print(f"[{ch} {i}/{args.runs}] {elapsed:.1f}초 finish={result.finish_reason} -> 통과")
                print(f"    {resp.footnote}")
            except OutputFormatError as e:
                s["fail"] += 1
                print(f"[{ch} {i}/{args.runs}] {elapsed:.1f}초 finish={result.finish_reason} -> 502 {e}")
                print(f"    (원문) {result.text}")
        table[ch] = s

    print("\n챕터                통과   502   잘림  평균토큰  최대토큰")
    total = 0
    for ch, s in table.items():
        avg = sum(s["tokens"]) / len(s["tokens"])
        total += s["pass"]
        print(f"{ch:<18}{s['pass']:>3}/{args.runs:<3}{s['fail']:>5}{s['length']:>6}{avg:>10.0f}{max(s['tokens']):>9}")
    print(f"합계 통과 {total}/{args.runs * len(table)}")
    print("통과 = 서버가 200 으로 보낼 문장. 문장 내용의 정확성은 사람이 따로 확인.")

if __name__ == "__main__":
    main()
