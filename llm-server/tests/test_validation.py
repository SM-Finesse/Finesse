"""응답 검증(app/validation.py) 테스트.

실행: llm-server 폴더에서  python -m pytest -v
"""
import json
from pathlib import Path

import pytest

from app.schemas import LightRequest
from app.validation import OutputFormatError, check_light_output, parse_light_output

SAMPLE = Path(__file__).parent / "samples" / "light_testuser.json"


@pytest.fixture
def available():
    """testuser 샘플에서 값이 있는 후보 11개."""
    data = json.loads(SAMPLE.read_text(encoding="utf-8"))
    return LightRequest.model_validate(data).available_stats()


def make_output(stats, summary="요약 문장입니다."):
    """테스트용 생성기 출력 문자열을 만든다."""
    return json.dumps(
        {
            "light_summary": summary,
            "highlights": [{"stat": s, "sentence": f"{s} 코멘트"} for s in stats],
        },
        ensure_ascii=False,
    )


# ---------- 형식(구조) 검사 ----------

def test_정상_출력은_형식_통과():
    resp = parse_light_output(make_output(["delta_plonk", "delta_vs_apm", "session_vs_slope"]))
    assert len(resp.highlights) == 3


def test_JSON이_아니면_형식_오류():
    with pytest.raises(OutputFormatError):
        parse_light_output("하이라이트는 다음과 같습니다: ...")


def test_light_summary가_객체면_형식_오류():
    # 베이스 모델 실측에서 실제로 나온 사례 (v1.2 4.5절)
    text = json.dumps({"light_summary": {"text": "요약"}, "highlights": []})
    with pytest.raises(OutputFormatError):
        parse_light_output(text)


def test_구버전_summary_hint가_있으면_형식_오류():
    text = json.dumps(
        {
            "light_summary": "요약",
            "highlights": [{"stat": "delta_plonk", "summary_hint": "stable", "sentence": "s"}],
        }
    )
    with pytest.raises(OutputFormatError):
        parse_light_output(text)


def test_빈_문장이면_형식_오류():
    text = json.dumps({"light_summary": "요약", "highlights": [{"stat": "delta_plonk", "sentence": "   "}]})
    with pytest.raises(OutputFormatError):
        parse_light_output(text)


# ---------- 내용 규칙 검사 ----------

def test_정상_3개는_문제_없음(available):
    resp = parse_light_output(make_output(["delta_plonk", "delta_vs_apm", "session_vs_slope"]))
    assert check_light_output(resp, available) == []


def test_4개면_개수_문제(available):
    resp = parse_light_output(
        make_output(["delta_plonk", "delta_vs_apm", "session_vs_slope", "delta_app"])
    )
    assert "count:4" in check_light_output(resp, available)


def test_2개면_개수_문제(available):
    resp = parse_light_output(make_output(["delta_plonk", "delta_vs_apm"]))
    assert "count:2" in check_light_output(resp, available)


def test_11개_밖의_키는_문제(available):
    # 백엔드 mock이 아직 쓰는 구버전 키들
    resp = parse_light_output(make_output(["delta_plonk", "comeback_rate", "tr_trend_delta"]))
    issues = check_light_output(resp, available)
    assert "unknown_stat:comeback_rate" in issues
    assert "unknown_stat:tr_trend_delta" in issues


def test_점_경로_표기는_문제(available):
    resp = parse_light_output(make_output(["defense.delta_vs_apm", "delta_plonk", "delta_app"]))
    assert "unknown_stat:defense.delta_vs_apm" in check_light_output(resp, available)


def test_null이던_지표를_고르면_문제(available):
    del available["delta_comeback"]  # 요청에서 null 이었다고 가정
    resp = parse_light_output(make_output(["delta_comeback", "delta_plonk", "delta_app"]))
    assert "unavailable_stat:delta_comeback" in check_light_output(resp, available)


def test_같은_지표를_두_번_고르면_문제(available):
    resp = parse_light_output(make_output(["delta_plonk", "delta_plonk", "delta_app"]))
    assert "duplicate_stat:delta_plonk" in check_light_output(resp, available)