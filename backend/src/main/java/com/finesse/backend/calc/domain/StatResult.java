package com.finesse.backend.calc.domain;

/**
 * 통계 계산 결과 — 백엔드에 전달되는 본 모듈의 최종 산출물 (설계서 19.5절).
 * 상대 식별 정보는 RivalryStats의 마스킹 닉네임뿐이다.
 * fancy·delta는 계산 가능한 매치(APM > 0, PPS ≥ 0.1)가 하나도 없으면 null이다 (6.5절·7장, v3.5).
 * highlightJudgement는 라이트 뷰 하이라이트 판정·선정 결과다 (30장, v4.2). 7개 인자 생성자로 만들면 null이다.
 * reliability는 하이라이트 후보 11개의 신뢰도다(「지표 신뢰도 판정 기준」). 화면 표시 전용이며,
 * 7·8개 인자 생성자로 만들면 null이다.
 */
public record StatResult(
        FancyStats fancy,
        DeltaStats delta,
        HighlightStats highlight,
        RecentWinLossStats recentWinLoss,
        ProfileWindowDeltaStats profileWindowDelta,
        RivalryStats rivalryStats,
        MatchSeriesStats series,
        HighlightJudgement highlightJudgement,
        ReliabilityReport reliability
) {
    /** v4.1까지의 생성자 — 백엔드 테스트가 쓰므로 남긴다. highlightJudgement는 null. */
    public StatResult(FancyStats fancy, DeltaStats delta, HighlightStats highlight,
                      RecentWinLossStats recentWinLoss, ProfileWindowDeltaStats profileWindowDelta,
                      RivalryStats rivalryStats, MatchSeriesStats series) {
        this(fancy, delta, highlight, recentWinLoss, profileWindowDelta, rivalryStats, series, null, null);
    }

    /** v4.2~v4.4의 생성자 — reliability는 null. */
    public StatResult(FancyStats fancy, DeltaStats delta, HighlightStats highlight,
                      RecentWinLossStats recentWinLoss, ProfileWindowDeltaStats profileWindowDelta,
                      RivalryStats rivalryStats, MatchSeriesStats series, HighlightJudgement highlightJudgement) {
        this(fancy, delta, highlight, recentWinLoss, profileWindowDelta, rivalryStats, series,
                highlightJudgement, null);
    }

    /** 하이라이트 판정 결과만 바꾼 사본 */
    public StatResult withHighlightJudgement(HighlightJudgement judgement) {
        return new StatResult(fancy, delta, highlight, recentWinLoss, profileWindowDelta, rivalryStats, series,
                judgement, reliability);
    }

    /** 신뢰도만 바꾼 사본 */
    public StatResult withReliability(ReliabilityReport report) {
        return new StatResult(fancy, delta, highlight, recentWinLoss, profileWindowDelta, rivalryStats, series,
                highlightJudgement, report);
    }
}