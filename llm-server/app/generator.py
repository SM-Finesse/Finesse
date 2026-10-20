"""③ 생성기 (light, heavy). Mock 과 Qwen(llama-cpp-python) 두 가지를 환경변수로 고른다.

환경변수 (서버를 켜기 전에 지정)
  LLM_GENERATOR       mock(기본) | llama
  LLM_MODEL_PATH      llama 일 때 GGUF 파일 경로 (PC마다 다르므로 코드에 쓰지 않음)
  LLM_JSON_MODE       off(기본) | json | schema   — 출력 형식 강제 (아래 설명)
  LLM_REPEAT_PENALTY  비우면 라이브러리 기본값, 예: 1.1  — 반복 생성 억제 (v1.2 7.2절 검토 항목)
  GPU 장치 지정은 코드가 아니라 실행 환경에서 한다 (Vulkan: GGML_VK_VISIBLE_DEVICES, v1.2 4.3절)

LLM_JSON_MODE (설계 문서에 없는 실험 옵션 — 시험 후 팀과 채택 여부 결정)
  off    : 프롬프트만으로 JSON 을 요구 (v1.2 기준 그대로)
  json   : 출력을 "유효한 JSON" 으로만 제한
  schema : light — 키 구성, 하이라이트 정확히 3개, stat 은 이번 요청에서 값이 있는 후보 중 하나로 제한
           (어느 지표를 고를지는 여전히 모델이 정한다 — 규칙으로 미리 고르는 것이 아님)
           heavy — 키 구성, chapter_id 는 요청값으로 제한

Mock 규칙(실제 서비스 로직이 아님): light 는 값이 있는 후보를 순서대로 앞 3개, heavy 는 고정 문장.
문장에 [Mock] 표시. light-v4(generate_text)는 목록 앞에서부터 그룹이 겹치지 않게 3개를 고르는 흉내만 낸다.
"""
import json
import logging
import os
import random
import re
import threading
import time
from dataclasses import dataclass

from app.light_v3 import LIGHT_V3_DECODING
from app.prompt import HEAVY_DECODING, LIGHT_DECODING
from app.schemas import HeavyRequest, LightRequest

# uvicorn 이 설정해 둔 로거 → 생성 시간·토큰 수가 서버 창에 보인다 (v1.2 13절 모니터링)
logger = logging.getLogger("uvicorn.error")

JSON_MODES = ("off", "json", "schema")


@dataclass
class GenerationResult:
    text: str  # 생성기가 낸 원문 (JSON 문자열이어야 함)
    finish_reason: str  # "stop" 정상 종료 / "length" 출력 예산에서 잘림 (v1.2 12.1절 기록 대상)


class GeneratorBusyError(Exception):
    """정해진 시간 안에 생성기(서버당 1건 잠금)를 얻지 못함 — 다른 요청이 생성 중 (헤비 시간 예산, 10/20)."""


class ContextOverflowError(Exception):
    """프롬프트 + 출력 예산이 n_ctx 를 넘어 생성을 시작할 수 없음."""


class MockGenerator:
    name = "mock"

    def generate_light(self, req: LightRequest, messages: list[dict[str, str]]) -> GenerationResult:
        # Mock 은 messages(프롬프트)를 읽지 않는다. Qwen 생성기와 같은 모양을 맞추려고 받기만 한다.
        picked = list(req.available_stats().items())[:3]
        output = {
            "light_summary": "[Mock] 실제 모델이 만든 요약이 아닙니다.",
            "highlights": [
                {"stat": key, "sentence": f"[Mock] {key} 값은 {value}입니다."}
                for key, value in picked
            ],
        }
        # 압축 JSON (v1.2 7.2절: 출력은 압축 JSON으로 고정)
        text = json.dumps(output, ensure_ascii=False, separators=(",", ":"))
        return GenerationResult(text=text, finish_reason="stop")

    def generate_light_text(self, req: LightRequest, messages: list[dict[str, str]]) -> GenerationResult:
        """light-v3: "번호 | 문장" 줄 형식. user 메시지의 [1]~[3] 후보를 그대로 고른다(선택 흉내만)."""
        lines = []
        for row in messages[-1]["content"].splitlines():
            m = re.match(r"\[(\d+)\] ([^(]+)\(.*\(([^()]*)\)\s*$", row)
            if m and len(lines) < 3:
                direction = m.group(3).split(",")[0].strip()
                lines.append(f"{m.group(1)} | 모의 응답입니다. {m.group(2).strip()}에서 {direction}.")
        lines.append("총평 | 모의 응답입니다. 실제 모델이 만든 총평이 아닙니다.")
        return GenerationResult(text="\n".join(lines), finish_reason="stop")

    def generate_text(self, label: str, messages: list[dict[str, str]], decoding: dict,
                      lock_timeout: float | None = None) -> GenerationResult:
        """light-v4 (A/B/C)·light-6call 공통 호출. label 로 어떤 단계인지 구분해 모양만 흉내 낸다."""
        user = messages[-1]["content"]
        if label.startswith("light5:"):
            # light-v5 Mock: 프롬프트의 예시 문장을 그대로 낸다 (흐름·연동 시험용, 실제 모델 아님)
            return GenerationResult(text=re.search(r"^예시: (.+)$", user, re.MULTILINE).group(1), finish_reason="stop")
        if label.startswith("light6:"):
            return GenerationResult(text=_mock_light6(label, user), finish_reason="stop")
        if label == "light:comment":
            name = re.search(r"^지표: ([^(]+)", user, re.MULTILINE).group(1).strip()
            direction = re.search(r"^방향: (.+)$", user, re.MULTILINE).group(1).strip()
            return GenerationResult(text=f"모의 응답 {name}, {direction}.", finish_reason="stop")
        if label == "light:summary":
            return GenerationResult(text="모의 응답입니다. 실제 모델이 만든 총평이 아닙니다.", finish_reason="stop")
        rows, groups = [], set()
        for row in user.splitlines():
            m = re.match(r"\[(\d+)\] (.+?)\(.*\) \[(.+?)\] 방향: (.+?) / 전체 기준: ", row)
            if not m or len(rows) >= 3:
                continue
            if label != "light:write" and m.group(3) in groups:
                continue
            groups.add(m.group(3))
            rows.append((m.group(1), m.group(2).strip(), m.group(4).strip()))
        if label == "light:select":
            return GenerationResult(text=", ".join(n for n, _, _ in rows), finish_reason="stop")
        lines = [f"{n} | 모의 응답입니다. {name}: {d}." for n, name, d in rows]
        lines.append("총평 | 모의 응답입니다. 실제 모델이 만든 총평이 아닙니다.")
        return GenerationResult(text="\n".join(lines), finish_reason="stop")

    def generate_heavy(self, req: HeavyRequest, messages: list[dict[str, str]],
                       lock_timeout: float | None = None) -> GenerationResult:
        # heavy-draft-v2: 모델은 각주 문장만 쓴다 (JSON 은 서버가 만든다). Mock 은 잠금이 없어 lock_timeout 무시
        return GenerationResult(text="[Mock] 실제 모델이 만든 각주가 아닙니다.", finish_reason="stop")


def _mock_light6(label: str, user: str) -> str:
    """light-6call Mock — 입력에서 정답을 계산해 형식에 맞게 낸다 (흐름·연동 시험용, 실제 모델 아님)."""
    from app.light6 import NAMES, example_sentence, expected_selection, judge_one
    by_name = {v: k for k, v in NAMES.items()}
    if label == "light6:judge":
        out = []
        for row in user.splitlines():
            m = re.match(r"\[(\d+)\] (.+?): (값 없음|값 (\S+)), Q1 (\S+), Q3 (\S+),", row)
            if m:
                v = None if m.group(4) is None else float(m.group(4).rstrip(","))
                z, d = judge_one(v, float(m.group(5).rstrip(",")), float(m.group(6).rstrip(",")))
                out.append(f"[{m.group(1)}] | {z} | {'-' if d is None else f'{d:+.1f}'}")
        return "\n".join(out)
    if label == "light6:select":
        judged, num = {}, {}
        for row in user.splitlines():
            m = re.match(r"\[(\d+)\] (.+?) \(그룹: .+?\): 영역 (\S+), 벗어난 정도 (\S+)", row)
            if m:
                k = by_name[m.group(2)]
                judged[k] = (m.group(3).rstrip(","), None if m.group(4) == "-" else float(m.group(4)))
                num[k] = m.group(1)
        return ", ".join(f"[{num[k]}]" for k in expected_selection(judged))
    if label == "light6:sentence":
        k = by_name[re.search(r"^지표: (.+)$", user, re.MULTILINE).group(1)]
        v = float(re.search(r"^값: (\S+)$", user, re.MULTILINE).group(1))
        return example_sentence(k, v)
    names = re.findall(r"^- (.+?): 값", user, re.MULTILINE)
    return f"맞붙은 상대들에 비해 {', '.join(names)} 쪽이 두드러집니다. 모의 응답이라 실제 모델의 요약이 아닙니다."


def light_response_schema(available: list[str]) -> dict:
    """LLM_JSON_MODE=schema 에서 쓰는 응답 JSON 스키마 (v1.2 5.2절 응답 형식)."""
    return {
        "type": "object",
        "properties": {
            "light_summary": {"type": "string"},
            "highlights": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "stat": {"type": "string", "enum": available},
                        "sentence": {"type": "string"},
                    },
                    "required": ["stat", "sentence"],
                    "additionalProperties": False,
                },
                "minItems": 3,
                "maxItems": 3,
            },
        },
        "required": ["light_summary", "highlights"],
        "additionalProperties": False,
    }


def heavy_response_schema(chapter_id: str) -> dict:
    """LLM_JSON_MODE=schema 에서 쓰는 heavy 응답 JSON 스키마 (v1.2 5.3절 응답 형식)."""
    return {
        "type": "object",
        "properties": {
            "chapter_id": {"type": "string", "enum": [chapter_id]},
            "footnote": {"type": "string"},
        },
        "required": ["chapter_id", "footnote"],
        "additionalProperties": False,
    }


class LlamaGenerator:
    name = "llama"

    def __init__(self, model_path: str, json_mode: str = "off", repeat_penalty: float | None = None,
                 n_ctx: int = 2048):
        if json_mode not in JSON_MODES:
            raise ValueError(f"LLM_JSON_MODE 는 {JSON_MODES} 중 하나여야 합니다: {json_mode}")
        from llama_cpp import Llama  # 무거운 라이브러리라 실제로 쓸 때만 불러온다

        self.json_mode = json_mode
        self.repeat_penalty = repeat_penalty
        # n_gpu_layers=-1: 모든 층을 GPU에 / n_ctx=2048: v1.2 4.1절 max_seq_length
        self._llm = Llama(model_path=model_path, n_gpu_layers=-1, n_ctx=n_ctx, verbose=False)
        # 서버당 동시 1건(v1.2 4.3절). 백엔드 대기열이 보장하지만 서버 쪽에서도 한 번 더 막는다.
        self._lock = threading.Lock()

    def warmup(self) -> None:
        """기동 직후 첫 요청이 느린 문제 대비 (v1.2 4.3절 배포 순서 6)."""
        with self._lock:
            self._llm.create_chat_completion(messages=[{"role": "user", "content": "ping"}], max_tokens=8)

    def generate_light(self, req: LightRequest, messages: list[dict[str, str]]) -> GenerationResult:
        schema = light_response_schema(list(req.available_stats())) if self.json_mode == "schema" else None
        return self._complete("light", messages, LIGHT_DECODING, schema)

    def generate_light_text(self, req: LightRequest, messages: list[dict[str, str]]) -> GenerationResult:
        # light-v3 는 "번호 | 문장" 줄만 쓰고 JSON 은 서버가 만든다 → JSON 모드를 적용하지 않음
        return self._complete("light", messages, LIGHT_V3_DECODING, None, text_only=True)

    def generate_text(self, label: str, messages: list[dict[str, str]], decoding: dict,
                      lock_timeout: float | None = None) -> GenerationResult:
        """light-v4 (A/B/C) 공통 호출: 줄·번호·문장만 쓰고 JSON 은 서버가 만든다 → JSON 모드 적용 안 함."""
        return self._complete(label, messages, decoding, None, text_only=True, lock_timeout=lock_timeout)

    def generate_heavy(self, req: HeavyRequest, messages: list[dict[str, str]],
                       lock_timeout: float | None = None) -> GenerationResult:
        # heavy-draft-v2 는 문장만 쓰므로 JSON 모드(json/schema)를 적용하지 않는다
        return self._complete(f"heavy:{req.chapter_id}", messages, HEAVY_DECODING, None, text_only=True,
                              lock_timeout=lock_timeout)

    def _complete(self, label: str, messages: list[dict[str, str]], decoding: dict,
                  schema: dict | None, text_only: bool = False,
                  lock_timeout: float | None = None) -> GenerationResult:
        kwargs = dict(decoding)
        # 요청마다 다른 seed. 지정하지 않으면 서버를 켤 때마다 같은 순서의 출력이 나와서,
        # 같은 시점에 켠 11번·13번이 똑같은 출력을 낸다 (10/8 두 서버 로그의 gen 토큰 수가 순서대로 일치).
        # 그러면 백엔드가 다른 서버로 재요청해도 같은 실패를 받을 수 있다.
        kwargs["seed"] = random.randrange(2**31)
        if self.repeat_penalty is not None:
            kwargs["repeat_penalty"] = self.repeat_penalty
        if text_only:
            pass
        elif self.json_mode == "json":
            kwargs["response_format"] = {"type": "json_object"}
        elif self.json_mode == "schema":
            kwargs["response_format"] = {"type": "json_object", "schema": schema}

        # lock_timeout: 이 시간 안에 차례가 오지 않으면 생성하지 않고 GeneratorBusyError (헤비 시간 예산)
        t_wait = time.perf_counter()
        if not self._lock.acquire(timeout=-1 if lock_timeout is None else max(0.0, lock_timeout)):
            raise GeneratorBusyError(f"{label}: {time.perf_counter() - t_wait:.1f}s 기다려도 차례가 오지 않음")
        try:
            waited = time.perf_counter() - t_wait
            t = time.perf_counter()
            try:
                out = self._llm.create_chat_completion(messages=messages, **kwargs)
            except ValueError as e:
                # llama-cpp-python 은 길이 초과를 ValueError("... exceed context window ...")로 알린다
                if "context window" in str(e):
                    raise ContextOverflowError(str(e)) from e
                raise
            elapsed = time.perf_counter() - t
        finally:
            self._lock.release()

        choice = out["choices"][0]
        usage = out.get("usage", {})
        finish = choice.get("finish_reason") or ""
        logger.info(
            "%s 생성 %.1fs (대기 %.1fs) finish=%s prompt=%s gen=%s seed=%s json_mode=%s",
            label, elapsed, waited, finish, usage.get("prompt_tokens"), usage.get("completion_tokens"), kwargs["seed"],
            self.json_mode,
        )
        return GenerationResult(text=choice["message"]["content"] or "", finish_reason=finish)


def build_generator() -> MockGenerator | LlamaGenerator:
    """환경변수를 읽어 생성기를 만든다. 아무것도 지정하지 않으면 Mock (CI·테스트용)."""
    kind = os.environ.get("LLM_GENERATOR", "mock").strip().lower()
    if kind == "mock":
        return MockGenerator()
    if kind == "llama":
        model_path = os.environ.get("LLM_MODEL_PATH")
        if not model_path:
            raise RuntimeError("LLM_GENERATOR=llama 이면 LLM_MODEL_PATH 에 GGUF 경로를 지정해야 합니다.")
        rp = os.environ.get("LLM_REPEAT_PENALTY", "").strip()
        return LlamaGenerator(
            model_path=model_path,
            json_mode=os.environ.get("LLM_JSON_MODE", "off").strip().lower(),
            repeat_penalty=float(rp) if rp else None,
        )
    raise RuntimeError(f"LLM_GENERATOR 는 mock 또는 llama 여야 합니다: {kind}")
