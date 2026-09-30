package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 최근 N판(기본 40) 승률과 전체 구간 승률 대비 증감 (설계서 11.9절).
 * 이미 수집된 현재 구간(최대 300판)의 부분집합으로 계산하며 추가 API 호출은 없다.
 */
@Component
public class RecentWinLossCalculator implements AnalyticsCalculator<RecentWinLossStats> {

    private final AnalyticsProperties properties;

    public RecentWinLossCalculator(AnalyticsProperties properties) {
        this.properties = properties;
    }

    @Override
    public CalculatorKey key() {
        return CalculatorKey.WIN_LOSS;
    }

    @Override
    public RecentWinLossStats calculate(AnalyticsContext context) {
        List<MatchHistory> newestFirst = context.matches().stream()
                .sorted(MatchOrdering.NEWEST_FIRST)
                .toList();

        int recentCount = Math.min(properties.recentWinLossWindow(), newestFirst.size());
        List<MatchResult> recentResults = newestFirst.subList(0, recentCount).stream()
                .map(MatchHistory::result)
                .toList();

        int wins = (int) recentResults.stream().filter(r -> r == MatchResult.WIN).count();
        int losses = recentCount - wins;
        double winRate = ratio(wins, recentCount);

        int overallCount = newestFirst.size();
        int overallWins = (int) newestFirst.stream().filter(MatchHistory::isWin).count();
        double overallWinRate = ratio(overallWins, overallCount);

        return new RecentWinLossStats(
                recentCount, wins, losses, winRate,
                overallCount, overallWinRate, winRate - overallWinRate,
                recentResults
        );
    }

    /** 분모 0 방어 (11.9.2절) — Cold Start Bypass 이후에는 실제로 발생하지 않는다. */
    static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}