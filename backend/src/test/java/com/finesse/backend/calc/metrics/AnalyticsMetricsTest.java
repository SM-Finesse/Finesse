package com.finesse.backend.calc.metrics;

import com.finesse.backend.calc.calculator.CalculatorKey;
import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.preprocessing.MatchValidity;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.AnalysisOutcome.ColdStartReason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AnalyticsMetrics metrics = new AnalyticsMetrics(registry);

    @Test
    void API_호출은_endpoint와_outcome별로_세고_지연시간을_기록한다() {
        metrics.recordApiCall("records", "success", 1_000_000);
        metrics.recordApiCall("records", "success", 2_000_000);
        metrics.recordApiCall("records", "retryable_failure", 3_000_000);

        assertThat(registry.get(AnalyticsMetrics.API_REQUESTS)
                .tags("endpoint", "records", "outcome", "success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(AnalyticsMetrics.API_REQUESTS)
                .tags("endpoint", "records", "outcome", "retryable_failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AnalyticsMetrics.API_LATENCY).tags("endpoint", "records").timer().count()).isEqualTo(3);
    }

    @Test
    void 서킷_상태를_숫자로_내보내고_차단_횟수를_센다() {
        AtomicReference<CircuitBreaker.State> state = new AtomicReference<>(CircuitBreaker.State.CLOSED);
        metrics.bindCircuitState(state::get);
        metrics.recordCircuitRejected();

        assertThat(registry.get(AnalyticsMetrics.CIRCUIT_STATE).gauge().value()).isEqualTo(0.0);
        state.set(CircuitBreaker.State.OPEN);
        assertThat(registry.get(AnalyticsMetrics.CIRCUIT_STATE).gauge().value()).isEqualTo(1.0);
        state.set(CircuitBreaker.State.HALF_OPEN);
        assertThat(registry.get(AnalyticsMetrics.CIRCUIT_STATE).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(AnalyticsMetrics.CIRCUIT_REJECTED).counter().count()).isEqualTo(1.0);
    }

    @Test
    void 계산_시간은_계산기별로_기록하고_결과를_그대로_돌려준다() {
        String result = metrics.timeCalculation(CalculatorKey.WIN_LOSS, () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(registry.get(AnalyticsMetrics.CALCULATION_DURATION)
                .tags("calculator", "win_loss").timer().count()).isEqualTo(1);
    }

    @Test
    void 제외_매치는_사유별로_더한다() {
        metrics.recordExcluded(Map.of(MatchValidity.INVALID_STATS, 3));
        metrics.recordExcluded(Map.of(MatchValidity.INVALID_STATS, 2));

        assertThat(registry.get(AnalyticsMetrics.EXCLUDED)
                .tags("reason", "invalid_stats").counter().count()).isEqualTo(5.0);
    }

    @Test
    void 분석_결과는_종류와_사유를_태그로_센다() {
        UserSummary summary = new UserSummary("user", "s", 15000, 2000, 60, null, 5);
        metrics.recordOutcome(new AnalysisOutcome.ColdStartBypass(summary, 5, ColdStartReason.FEW_GAMES_TOTAL));
        metrics.recordOutcome(new AnalysisOutcome.UserNotFound("user"));
        metrics.recordOutcome(new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED));

        assertThat(registry.get(AnalyticsMetrics.OUTCOME)
                .tags("type", "cold_start", "reason", "few_games_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AnalyticsMetrics.OUTCOME)
                .tags("type", "user_not_found", "reason", "none").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AnalyticsMetrics.OUTCOME)
                .tags("type", "collection_failed", "reason", "failed").counter().count()).isEqualTo(1.0);
    }
}