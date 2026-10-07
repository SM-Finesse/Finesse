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
문장에 [Mock] 표시.
"""
import json
import logging
import os
import threading
import time
from dataclasses import dataclass

from app.prompt import HEAVY_DECODING, LIGHT_DECODING
from app.schemas import HeavyRequest, LightRequest

# uvicorn 이 설정해 둔 로거 → 생성 시간·토큰 수가 서버 창에 보인다 (v1.2 13절 모니터링)
logger = logging.getLogger("uvicorn.error")

JSON_MODES = ("off", "json", "schema")


@dataclass
class GenerationResult:
    text: str  # 생성기가 낸 원문 (JSON 문자열이어야 함)
    finish_reason: str  # "stop" 정상 종료 / "length" 출력 예산에서 잘림 (v1.2 12.1절 기록 대상)


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

    def generate_heavy(self, req: HeavyRequest, messages: list[dict[str, str]]) -> GenerationResult:
        output = {"chapter_id": req.chapter_id, "footnote": f"[Mock] {req.chapter_id} 챕터 각주입니다."}
        text = json.dumps(output, ensure_ascii=False, separators=(",", ":"))
        return GenerationResult(text=text, finish_reason="stop")


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

    def generate_heavy(self, req: HeavyRequest, messages: list[dict[str, str]]) -> GenerationResult:
        schema = heavy_response_schema(req.chapter_id) if self.json_mode == "schema" else None
        return self._complete(f"heavy:{req.chapter_id}", messages, HEAVY_DECODING, schema)

    def _complete(self, label: str, messages: list[dict[str, str]], decoding: dict,
                  schema: dict | None) -> GenerationResult:
        kwargs = dict(decoding)
        if self.repeat_penalty is not None:
            kwargs["repeat_penalty"] = self.repeat_penalty
        if self.json_mode == "json":
            kwargs["response_format"] = {"type": "json_object"}
        elif self.json_mode == "schema":
            kwargs["response_format"] = {"type": "json_object", "schema": schema}

        with self._lock:
            t = time.perf_counter()
            try:
                out = self._llm.create_chat_completion(messages=messages, **kwargs)
            except ValueError as e:
                # llama-cpp-python 은 길이 초과를 ValueError("... exceed context window ...")로 알린다
                if "context window" in str(e):
                    raise ContextOverflowError(str(e)) from e
                raise
            elapsed = time.perf_counter() - t

        choice = out["choices"][0]
        usage = out.get("usage", {})
        finish = choice.get("finish_reason") or ""
        logger.info(
            "%s 생성 %.1fs finish=%s prompt=%s gen=%s json_mode=%s",
            label, elapsed, finish, usage.get("prompt_tokens"), usage.get("completion_tokens"), self.json_mode,
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
