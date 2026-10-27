"""테스트 공통 설정.

10/20: 서비스 /v1/comment/light 기본값을 v2(light-baseline-v2)로 되돌렸다. 실험용 light-v3·v4·six 테스트는
예전 기본값(v3) 기준으로 쓰여 있으므로 테스트에서만 v3 로 둔다. 기본값 자체는 test_light_comment.py 에서 따로 확인한다.
"""
import pytest


@pytest.fixture(autouse=True)
def _light_prompt_v3(monkeypatch):
    monkeypatch.setenv("LLM_LIGHT_PROMPT", "v3")
