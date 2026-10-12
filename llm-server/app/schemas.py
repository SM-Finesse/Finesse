"""light 요청 스키마 (백엔드 -> 추론 서버).

근거: LLM/AI 파트 설계 v1.2 5.1절(후보 11개), 5.2절(요청 스키마)
"""

from pydantic import BaseModel, ConfigDict, Field

# 하이라이트 후보 11개의 평탄 키 (v1.2 5.1절 표 순서).
# 응답의 highlights[].stat 은 반드시 이 중 하나여야 한다.
CANDIDATE_KEYS = (
    "delta_opener",
    "delta_plonk",
    "delta_stride",
    "delta_inf_ds",
    "delta_app",
    "delta_weighted_app",
    "delta_vs_apm",
    "delta_cheese_index",
    "strength_split",
    "delta_comeback",
    "session_vs_slope",
)


class _Strict(BaseModel):
    # forbid: 정의되지 않은 필드가 오면 거절한다.
    #   (예: tr_trend_delta 는 light 후보에서 제외, Est.TR 계열은 입력 금지)
    # allow_inf_nan=False: NaN·Infinity 값을 거절한다.
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class FixedMetrics(_Strict):
    win_rate: float = Field(ge=0, le=1)
    tr_trend: list[float]


# 모든 후보는 float | None + 기본값 None.
# null 후보를 "필드 생략"으로 보낼지 "null 값"으로 보낼지 미정이므로
# (v1.2 5.1절 확인 필요) 두 형태를 모두 받아 똑같이 "후보 아님"으로 처리한다.
class PlaystyleRelative(_Strict):
    delta_opener: float | None = None
    delta_plonk: float | None = None
    delta_stride: float | None = None
    delta_inf_ds: float | None = None


class Attack(_Strict):
    delta_app: float | None = None
    delta_weighted_app: float | None = None


class Defense(_Strict):
    delta_vs_apm: float | None = None
    delta_cheese_index: float | None = None


class DeltaMetrics(_Strict):
    playstyle_relative: PlaystyleRelative = Field(default_factory=PlaystyleRelative)
    attack: Attack = Field(default_factory=Attack)
    defense: Defense = Field(default_factory=Defense)
    strength_split: float | None = None
    delta_comeback: float | None = None
    session_vs_slope: float | None = None


class LightRequest(_Strict):
    fixed_metrics: FixedMetrics
    delta_metrics: DeltaMetrics

    def available_stats(self) -> dict[str, float]:
        """값이 있는 후보만 {평탄 키: 값}으로 돌려준다.

        null 이거나 생략된 후보는 여기서 빠진다.
        LLM이 고를 수 있는 후보 = 이 dict의 키.
        """
        d = self.delta_metrics
        flat = {
            **d.playstyle_relative.model_dump(),
            **d.attack.model_dump(),
            **d.defense.model_dump(),
            "strength_split": d.strength_split,
            "delta_comeback": d.delta_comeback,
            "session_vs_slope": d.session_vs_slope,
        }
        return {k: flat[k] for k in CANDIDATE_KEYS if flat[k] is not None}