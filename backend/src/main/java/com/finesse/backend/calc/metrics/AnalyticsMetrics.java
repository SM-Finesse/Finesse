package com.finesse.backend.calc.metrics;

import com.finesse.backend.calc.calculator.CalculatorKey;
import com.finesse.backend.calc.preprocessing.MatchValidity;
import com.finesse.backend.calc.service.AnalysisOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * calc 모듈 운영 지표 (설계서 27.3절). 태그 값은 고정된 소수의 값만 쓴다(유저 이름·URL·예외 메시지 금지 — 카디널리티 폭증 방지).
 * MeterRegistry 빈이 없으면(Actuator 미사용) 전역 레지스트리에 기록되며, 등록된 저장소가 없으면 버려진다.
 */
@Component
public class AnalyticsMetrics {

    static final String API_REQUESTS = "finesse.tetrio.api.requests";
    static final String API_LATENCY = "finesse.tetrio.api.latency";
    static final String CIRCUIT_STATE = "finesse.tetrio.circuit.state";
    static final String CIRCUIT_REJECTED = "finesse.tetrio.circuit.rejected";
    static final String CALCULATION_DURATION = "finesse.analytics.calculation.duration";
    static final String OUTCOME = "finesse.analytics.outcome";
    static final String EXCLUDED = "finesse.analytics.preprocess.excluded";

    private final MeterRegistry registry;

    @Autowired
    public AnalyticsMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(() -> Metrics.globalRegistry));
    }

    public AnalyticsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 테스트·단독 사용용 — 기록은 하지만 어디에도 내보내지 않는다. */
    public static AnalyticsMetrics noop() {
        return new AnalyticsMetrics(new SimpleMeterRegistry());
    }

    // ── TETR.IO 호출 ─────────────────────────────────────────────

    /**
     * HTTP 시도 1회 기록 (재시도 포함).
     * @param endpoint summary | records
     * @param outcome  success | retryable_failure | failure | not_found
     */
    public void recordApiCall(String endpoint, String outcome, long elapsedNanos) {
        Counter.builder(API_REQUESTS).tag("endpoint", endpoint).tag("outcome", outcome).register(registry).increment();
        Timer.builder(API_LATENCY).tag("endpoint", endpoint).register(registry).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordCircuitRejected() {
        Counter.builder(CIRCUIT_REJECTED).register(registry).increment();
    }

    /** 0 = CLOSED, 1 = OPEN, 2 = HALF_OPEN, 3 = 그 밖의 상태 */
    public void bindCircuitState(Supplier<CircuitBreaker.State> state) {
        Gauge.builder(CIRCUIT_STATE, () -> stateCode(state.get())).register(registry);
    }

    static int stateCode(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 3;
        };
    }

    // ── 분석 ─────────────────────────────────────────────────────

    public <T> T timeCalculation(CalculatorKey key, Supplier<T> calculation) {
        return Timer.builder(CALCULATION_DURATION)
                .tag("calculator", key.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .record(calculation);
    }

    public void recordExcluded(Map<MatchValidity, Integer> excludedByReason) {
        excludedByReason.forEach((reason, count) ->
                Counter.builder(EXCLUDED).tag("reason", reason.name().toLowerCase(Locale.ROOT))
                        .register(registry).increment(count));
    }

    public void recordOutcome(AnalysisOutcome outcome) {
        String type;
        String reason;
        if (outcome instanceof AnalysisOutcome.Analyzed) {
            type = "analyzed";
            reason = "none";
        } else if (outcome instanceof AnalysisOutcome.ColdStartBypass c) {
            type = "cold_start";
            reason = c.reason().name().toLowerCase(Locale.ROOT);
        } else if (outcome instanceof AnalysisOutcome.UserNotFound) {
            type = "user_not_found";
            reason = "none";
        } else if (outcome instanceof AnalysisOutcome.CollectionFailed f) {
            type = "collection_failed";
            reason = f.status().name().toLowerCase(Locale.ROOT);
        } else {
            type = "unknown";
            reason = "none";
        }
        Counter.builder(OUTCOME).tag("type", type).tag("reason", reason).register(registry).increment();
    }
}