package com.finesse.backend.calc.service;

import com.finesse.backend.calc.calculator.CalculatorRegistry;
import com.finesse.backend.calc.calculator.DeltaStatCalculator;
import com.finesse.backend.calc.calculator.FancyMathCalculator;
import com.finesse.backend.calc.calculator.HighlightStatCalculator;
import com.finesse.backend.calc.calculator.MatchSeriesCalculator;
import com.finesse.backend.calc.calculator.ProfileWindowDeltaCalculator;
import com.finesse.backend.calc.calculator.RecentWinLossCalculator;
import com.finesse.backend.calc.calculator.RivalryCalculator;
import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.RecordPage;
import com.finesse.backend.calc.collector.TetrIoApi;
import com.finesse.backend.calc.collector.TetrIoCollector;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoRateLimitedException;
import com.finesse.backend.calc.metrics.AnalyticsMetrics;
import com.finesse.backend.calc.preprocessing.MatchPreprocessor;
import com.finesse.backend.calc.preprocessing.MatchScopedPseudonymizer;
import com.finesse.backend.calc.preprocessing.MatchValidator;
import com.finesse.backend.calc.preprocessing.NicknamePseudonymizer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * collect 브랜치 — 요약 조회·기록 수집이 429로 실패하면 CollectionFailed(RATE_LIMITED)로 끝나는지.
 */
class RateLimitedOutcomeTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    private static TetrIoApi api(RuntimeException summaryError, RuntimeException recordsError) {
        return new TetrIoApi() {
            @Override
            public UserSummary fetchLeagueSummary(String username, String sessionId) {
                if (summaryError != null) throw summaryError;
                return new UserSummary(username, "s", 15000, 2000, 60, null, 500, 80.0, 1.5, 160.0);
            }

            @Override
            public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
                throw recordsError;
            }
        };
    }

    private static AnalysisOutcome analyze(TetrIoApi api) {
        CollectorProperties cp = new CollectorProperties("http://unused", Duration.ZERO,
                3, 100, 365, 30, 3, Duration.ofSeconds(3), Duration.ofSeconds(5));
        AnalyticsProperties ap = new AnalyticsProperties(10, 300, 40);
        CalculatorRegistry registry = new CalculatorRegistry(List.of(
                new FancyMathCalculator(), new DeltaStatCalculator(), new HighlightStatCalculator(),
                new RecentWinLossCalculator(ap), new ProfileWindowDeltaCalculator(), new RivalryCalculator(),
                new MatchSeriesCalculator()));
        return new StatCalculatorFacade(new TetrIoCollector(api, cp),
                new MatchPreprocessor(new MatchValidator(), new MatchScopedPseudonymizer()),
                registry, new NicknamePseudonymizer(), ap, Clock.fixed(NOW, ZoneOffset.UTC), AnalyticsMetrics.noop())
                .analyze("abc");
    }

    @Test
    void 요약_조회가_429면_RATE_LIMITED() {
        AnalysisOutcome o = analyze(api(new TetrIoRateLimitedException(null), null));

        assertThat(o).isEqualTo(new AnalysisOutcome.CollectionFailed(CollectionStatus.RATE_LIMITED));
    }

    @Test
    void 기록_수집이_429면_RATE_LIMITED() {
        AnalysisOutcome o = analyze(api(null, new TetrIoRateLimitedException(null)));

        assertThat(o).isEqualTo(new AnalysisOutcome.CollectionFailed(CollectionStatus.RATE_LIMITED));
    }

    @Test
    void 그_밖의_실패는_기존처럼_FAILED() {
        assertThat(analyze(api(new TetrIoApiException("5xx"), null)))
                .isEqualTo(new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED));
        assertThat(analyze(api(null, new TetrIoApiException("5xx"))))
                .isEqualTo(new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED));
    }
}
