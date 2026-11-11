"""논문 실험용 생성 (POST /v1/paper/generate, GET /v1/paper/meta) — 서비스 엔드포인트와 분리.

요청서 「[논문용 엔드포인트 추가 요청] llm-server /v1/paper/generate」(11/09) 기준.
서비스와 다른 점
  - 프롬프트 안의 무작위 선택(비교 표현·예시 문구)을 rng_seed 로 고정한다. rng_seed 는 seed·call_type·slot·payload 로만
    정해지므로 같은 요청이면 늘 같고, P0·P1·FT 조건끼리도 같다.
  - llama 시드 = 요청의 seed. 생성은 1회만 하고 재시도·검사·대체 문장·후처리(부호·이름 정리)·시간 마감이 없다.
  - 디코딩은 서비스 값 그대로 + repeat_penalty 1.1 고정 (서버 환경변수와 상관없이 같은 값).
  - 응답에 실제로 넣은 messages 전문, 원문, finish_reason, 토큰 수, 시간을 담는다.

켜기: LLM_PAPER_ENDPOINT=on (기본 off → 404). 생성기 잠금은 서비스와 같이 쓰므로, 실험 중에는 서비스 요청이 그만큼 기다린다.
조건
  P1 : 서비스 프롬프트 (lc.build_sentence / lc.build_summary / build_heavy_messages)
  FT : P1 과 같은 프롬프트 (파인튜닝 모델로 서버를 켜서 실행)
  P0 : 같은 데이터 + 규칙 없는 기본 지시 1~2문장 (아래 P0_*). 문구는 협의 중 — 바꾸면 P0_PROMPT_VERSION 도 바꾼다.
"""
import hashlib
import json
import os
import random
from pathlib import Path
from typing import Any, Literal

from pydantic import model_validator

from app import light_comment as lc
from app.prompt import (
    HEAVY_CHAPTERS,
    HEAVY_DECODING,
    HEAVY_LABELS,
    HEAVY_PROMPT_VERSION,
    build_heavy_messages,
    fmt_num,
)
from app.schemas import HeavyRequest, SentenceRequest, SummaryRequest, _Strict

PAPER_ENDPOINT_VERSION = "paper-endpoint-v1-20261109"
P0_PROMPT_VERSION = "paper-p0-v2-20261111"       # 11/11 값의 기준 추가 (④ 배치명세 2.1절). 이전: paper-p0-v1-20261110, paper-p0-draft-20261109
PAPER_REPEAT_PENALTY = 1.1

PAPER_CHAPTERS = ("tr_trend", "playstyle", "attack", "defense", "strength_split", "comeback_rate", "session_vs_slope")
SLOTS = {"sentence": ("pick1", "pick2", "pick3"), "summary": ("summary",), "heavy": PAPER_CHAPTERS}

# ---- P0 기본 지시 (규칙·방향·예시 없음) — 11/10 팀 메모 「P0 예시 프롬프트」 ----
# P1 system 의 첫머리(무엇을 몇 자로 쓰는지)와 끝줄(출력 형식)만 남기고, 규칙 목록·방향·예시·비교 표현은 뺀 것.
# P1 과 같은 구조: system = 지시, user = 데이터.
P0_SENTENCE = ("TETR.IO 전적 분석 페이지 첫 화면의 하이라이트 문장 하나를 한국어로 씁니다.\n"
               "아래 지표 값이 이 플레이어에 대해 무엇을 뜻하는지 한 문장(40~60자)으로 쓰세요.\n"
               "문장 하나만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요.")
P0_SUMMARY = ("TETR.IO 전적 분석 페이지 첫 화면의 요약 코멘트를 한국어로 씁니다.\n"
              "아래 지표 세 개를 묶어 이 플레이어의 전적을 2~3문장(70~100자)으로 요약하세요.\n"
              "요약 문장만 쓰세요. 제목, 목록, 따옴표는 쓰지 마세요.")
P0_HEAVY = ("TETR.IO 전적 분석 리포트의 '{title}' 챕터 아래에 붙는 각주를 한국어로 씁니다.\n"
            "입력된 값이 무엇을 뜻하는지 1-2문장(40~70자)으로 짧게 설명하세요.\n"
            "각주 문장만 쓰세요. 제목·번호·목록·따옴표·JSON은 쓰지 마세요.")


# ---- P0 user 의 원정보 — 값의 기준 (v2, 11/11) ----
# 값이 무엇과의 차이인지는 '정보'이지 규칙이 아니다. 빼면 P0 의 비교 기준(C1 (i)) 위반이 정보 부족을 재게 된다.
# light 는 긴 구절(…(내 값 − 상대 값)), heavy 는 짧은 구절. 쓰는 법(비교 표현·서술어·강도)은 넣지 않는다.
P0_BASIS_LIGHT = {
    "delta_opener": "맞붙은 상대와의 성향 차이(내 값 − 상대 값)",
    "delta_plonk": "맞붙은 상대와의 성향 차이(내 값 − 상대 값)",
    "delta_stride": "맞붙은 상대와의 성향 차이(내 값 − 상대 값)",
    "delta_inf_ds": "맞붙은 상대와의 성향 차이(내 값 − 상대 값)",
    "delta_app": "맞붙은 상대와의 차이(내 값 − 상대 값)",
    "delta_weighted_app": "맞붙은 상대와의 차이(내 값 − 상대 값)",
    "delta_vs_apm": "맞붙은 상대와의 차이(내 값 − 상대 값)",
    "delta_cheese_index": "맞붙은 상대와의 차이(내 값 − 상대 값)",
    "strength_split": "강한 상대 구간 승률 − 약한 상대 구간 승률",
    "delta_comeback": "역전승 비율 − 역전패 비율",
    "session_vs_slope": "한 매치 안에서 라운드당 공방 능력 변화",
}
P0_BASIS_HEAVY = {
    "tr_trend_delta": "최근 구간 평균 TR − 전체 평균 TR",
    "delta_opener": "맞붙은 상대와의 성향 차이",
    "delta_plonk": "맞붙은 상대와의 성향 차이",
    "delta_stride": "맞붙은 상대와의 성향 차이",
    "delta_inf_ds": "맞붙은 상대와의 성향 차이",
    "delta_app": "맞붙은 상대와의 차이",
    "delta_weighted_app": "맞붙은 상대와의 차이",
    "delta_vs_apm": "맞붙은 상대와의 차이",
    "delta_cheese_index": "맞붙은 상대와의 차이",
    "strength_split": "강한 상대 구간 승률 − 약한 상대 구간 승률",
    "comeback_rate": "점수가 뒤졌던 매치 중 이긴 비율",
    "comeback_rate_against": "점수가 앞섰던 매치 중 진 비율",
    "session_vs_slope": "한 매치 안에서 라운드당 공방 능력 변화",
}
# heavy 이름 — 서버 HEAVY_LABELS 에서 괄호 안 설명을 뺀 것 (그 설명은 값의 기준으로 옮김). 나머지는 HEAVY_LABELS 그대로.
P0_HEAVY_NAMES = {"tr_trend_delta": "TR 추이", "strength_split": "상대 강도별 승률"}
_P0_UNSIGNED = ("comeback_rate", "comeback_rate_against", "comeback_opportunities", "comeback_won",
                "comeback_against_opportunities", "comeback_against_allowed")


def _p0_heavy_line(k: str, v) -> str:
    name = P0_HEAVY_NAMES.get(k, HEAVY_LABELS.get(k, k))
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        val = fmt_num(k, v, signed=k not in _P0_UNSIGNED)      # 횟수·비율은 부호 없이 (v1: '+16')
    else:
        val = canonical_json(v)
    basis = P0_BASIS_HEAVY.get(k)
    return f"- {name}: {val}" + (f" (값의 기준: {basis})" if basis else "")


def _p0(system: str, lines: list[str]) -> list[dict[str, str]]:
    return [{"role": "system", "content": system}, {"role": "user", "content": "\n".join(lines)}]


def enabled() -> bool:
    return os.getenv("LLM_PAPER_ENDPOINT", "off").strip().lower() in ("on", "1", "true")


class PaperRequest(_Strict):
    condition: Literal["P0", "P1", "FT"]
    seed: int
    call_type: Literal["sentence", "summary", "heavy"]
    slot: str
    payload: dict[str, Any]

    @model_validator(mode="after")
    def _check(self):
        if not 0 <= self.seed < 2**31:
            raise ValueError("seed 는 0 이상 2^31 미만")
        if self.slot not in SLOTS[self.call_type]:
            raise ValueError(f"{self.call_type} 의 slot 은 {SLOTS[self.call_type]} 중 하나 (받음: {self.slot})")
        if self.call_type == "heavy" and self.payload.get("chapter_id") != self.slot:
            raise ValueError("heavy 는 payload.chapter_id 와 slot 이 같아야 함 (rivals 제외 7챕터)")
        return self


def canonical_json(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def rng_seed_of(req: PaperRequest) -> int:
    """seed + call_type + slot + payload(키 정렬 JSON)의 SHA256 앞 8바이트를 63비트로 (백엔드 Java long 에 들어가게).
    조건(P0/P1/FT)은 넣지 않는다 → 같은 입력이면 조건끼리 같은 예시·비교 표현."""
    key = canonical_json({"seed": req.seed, "call_type": req.call_type, "slot": req.slot, "payload": req.payload})
    return int.from_bytes(hashlib.sha256(key.encode("utf-8")).digest()[:8], "big") & (2**63 - 1)


def sha256_messages(messages: list[dict[str, str]]) -> str:
    return hashlib.sha256(canonical_json(messages).encode("utf-8")).hexdigest()


def build_paper_prompt(req: PaperRequest, rng: random.Random) -> tuple[list[dict[str, str]], dict, str]:
    """(messages, 디코딩, prompt_version). 422 사유는 ValueError 로 올린다."""
    if req.call_type == "sentence":
        p = SentenceRequest.model_validate(req.payload)
        zone = _zone(p.stat, p.value, p.zone)
        dec = lc.LIGHT_COMMENT_DECODING["sentence"]
        if req.condition == "P0":
            return _p0(P0_SENTENCE, [f"지표: {lc.NAMES[p.stat]}", f"값: {lc.fmt_value(p.stat, p.value)}",
                                     f"값의 기준: {P0_BASIS_LIGHT[p.stat]}",
                                     f"영역(비슷한 실력대 기준): {zone}"]), dec, P0_PROMPT_VERSION
        return lc.build_sentence(p.stat, p.value, zone, rng), dec, lc.LIGHT_COMMENT_VERSION
    if req.call_type == "summary":
        p = SummaryRequest.model_validate(req.payload)
        if len({i.stat for i in p.items}) != len(p.items):
            raise ValueError("같은 stat 이 두 번 들어옴")
        items = [(i.stat, i.value, _zone(i.stat, i.value, i.zone)) for i in p.items]
        dec = lc.LIGHT_COMMENT_DECODING["summary"]
        if req.condition == "P0":
            lines = ["지표"] + [f"- {lc.NAMES[k]}: 값 {lc.fmt_value(k, v)}, 값의 기준: {P0_BASIS_LIGHT[k]}, 영역: {z}"
                               for k, v, z in items]
            return _p0(P0_SUMMARY, lines), dec, P0_PROMPT_VERSION
        return lc.build_summary(items, rng), dec, lc.LIGHT_COMMENT_VERSION
    p = HeavyRequest.model_validate(req.payload)
    if req.condition == "P0":
        title = HEAVY_CHAPTERS[p.chapter_id]["title"]
        lines = [f"챕터: {title}"]
        lines += [_p0_heavy_line(k, v) for k, v in p.data.items() if v is not None]
        return _p0(P0_HEAVY.format(title=title), lines), HEAVY_DECODING, P0_PROMPT_VERSION
    return build_heavy_messages(p, rng=rng), HEAVY_DECODING, HEAVY_PROMPT_VERSION


def _zone(stat: str, value: float, zone: str | None) -> str:
    if zone == "없음":
        raise ValueError(f"{stat}: 영역 '없음'은 코멘트를 쓸 수 없음")
    return lc.resolve_zone(stat, value, zone)


def paper_decoding(base: dict) -> dict:
    return {**base, "repeat_penalty": PAPER_REPEAT_PENALTY}


# ---- /v1/paper/meta ----
_meta_cache: dict[str, Any] = {}


def file_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def model_sha256() -> str | None:
    """LLM_MODEL_SHA256 가 있으면 그 값, 없으면 모델 파일을 한 번 계산해 둔다 (기동 때 호출)."""
    if "model_sha256" not in _meta_cache:
        path = os.getenv("LLM_MODEL_PATH")
        given = os.getenv("LLM_MODEL_SHA256", "").strip().lower()
        _meta_cache["model_sha256"] = given or (file_sha256(path) if path and Path(path).is_file() else None)
    return _meta_cache["model_sha256"]


def server_code() -> str | None:
    """LLM_SERVER_CODE(직접 지정) → 레포 .git 의 HEAD 커밋. zip 으로 받아 .git 이 없으면 None."""
    if os.getenv("LLM_SERVER_CODE"):
        return os.getenv("LLM_SERVER_CODE")
    git = Path(__file__).resolve().parents[2] / ".git"
    try:
        head = (git / "HEAD").read_text(encoding="utf-8").strip()
        if not head.startswith("ref: "):
            return head
        ref = head[5:]
        if (git / ref).is_file():
            return (git / ref).read_text(encoding="utf-8").strip()
        for line in (git / "packed-refs").read_text(encoding="utf-8").splitlines():
            if line.endswith(" " + ref):
                return line.split()[0]
    except OSError:
        return None
    return None


def llama_cpp_version() -> str | None:
    try:
        import llama_cpp
        return getattr(llama_cpp, "__version__", None)
    except ImportError:
        return None


def meta(generator) -> dict:
    path = os.getenv("LLM_MODEL_PATH")
    return {
        "paper_endpoint_version": PAPER_ENDPOINT_VERSION,
        "generator": generator.name,
        "model_file": Path(path).name if path else None,
        "model_sha256": model_sha256() if generator.name == "llama" else None,
        "llama_cpp_version": llama_cpp_version(),
        "server_code": server_code(),
        "prompt_versions": {"P0": P0_PROMPT_VERSION, "P1_light": lc.LIGHT_COMMENT_VERSION,
                            "P1_heavy": HEAVY_PROMPT_VERSION},
        "n_ctx": getattr(generator, "n_ctx", None),
        "repeat_penalty": PAPER_REPEAT_PENALTY,
    }
