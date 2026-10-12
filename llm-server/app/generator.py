"""③ 생성기. 지금은 Mock, 나중에 Qwen(llama-cpp-python)으로 교체한다.

Mock 규칙(실제 서비스 로직이 아님):
  - 값이 있는 후보를 CANDIDATE_KEYS 순서대로 앞에서 3개 고른다.
  - 문장에는 입력값을 그대로 인용하고, [Mock] 표시를 붙여 실제 모델 응답과 구분한다.
실제 선정은 LLM이 한다. 규칙으로 미리 고르는 방식은 기각됐다(v1.2 3절).
"""
import json
from dataclasses import dataclass

from app.schemas import LightRequest


@dataclass
class GenerationResult:
    text: str  # 생성기가 낸 원문 (JSON 문자열이어야 함)
    finish_reason: str  # "stop" 정상 종료 / "length" 출력 예산에서 잘림 (v1.2 12.1절 기록 대상)


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