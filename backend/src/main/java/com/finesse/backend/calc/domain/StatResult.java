package com.finesse.backend.calc.domain;

/**
 * 통계 계산 결과 — 백엔드에 전달되는 본 모듈의 최종 산출물 (설계서 19.5절).
 * 상대 식별 정보는 RivalryStats의 마스킹 닉네임뿐이다.
 * fancy·delta는 계산 가능한 매치(APM > 0, PPS ≥ 0.1)가 하나도 없으면 null이다 (6.5절·7장, v3.5).
 */
public record StatResult(
        FancyStats fancy,
        DeltaStats delta,
        HighlightStats highlight,
        RecentWinLossStats recentWinLoss,
        ProfileWindowDeltaStats profileWindowDelta,
        RivalryStats rivalryStats,
        MatchSeriesStats series
) {}