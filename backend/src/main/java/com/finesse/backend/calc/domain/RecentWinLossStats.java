package com.finesse.backend.calc.domain;

import java.util.List;

/**
 * 최근 승률 분포 (설계서 11.9.3절).
 * 승률은 0~1 비율이며, %·%p 표시 변환은 API 응답 경계(백엔드) 책임이다.
 * recentResults는 최신순이다 (UI 40칸 승패 그리드용).
 */
public record RecentWinLossStats(
        int recentCount,
        int wins,
        int losses,
        double winRate,
        int overallCount,
        double overallWinRate,
        double deltaVsOverall,
        List<MatchResult> recentResults
) {
    public RecentWinLossStats {
        recentResults = List.copyOf(recentResults);
    }
}