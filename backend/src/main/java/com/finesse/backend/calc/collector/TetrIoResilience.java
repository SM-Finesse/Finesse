package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.metrics.AnalyticsMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * TETR.IO 호출 1건(페이지 1개)에 Retry와 CircuitBreaker를 씌운다 (설계서 3.9~3.11절).
 * 순서: CircuitBreaker → Retry → (RateLimiter + HTTP). CircuitBreaker가 OPEN이면 재시도 루프 자체를 돌리지 않는다.
 * 재시도·실패 집계 대상은 retryable=true인 TetrIoApiException(네트워크·타임아웃·5xx)뿐이다.
 * 프로세스 전체에서 하나의 CircuitBreaker를 공유해 외부 API를 보호한다.
 */
@Component
public class TetrIoResilience {

    static final String NAME = "tetrIoApi";

    private static final Predicate<Throwable> TRANSIENT =
            e -> e instanceof TetrIoApiException t && t.retryable();

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final AnalyticsMetrics metrics;

    @Autowired
    public TetrIoResilience(CollectorProperties properties, AnalyticsMetrics metrics) {
        this(properties.maxRetryAttempts(), Duration.ofMillis(500), defaultCircuitBreakerConfig(), metrics);
    }

    TetrIoResilience(int maxAttempts, Duration initialWait, CircuitBreakerConfig circuitBreakerConfig) {
        this(maxAttempts, initialWait, circuitBreakerConfig, AnalyticsMetrics.noop());
    }

    TetrIoResilience(int maxAttempts, Duration initialWait, CircuitBreakerConfig circuitBreakerConfig,
                     AnalyticsMetrics metrics) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)                    // 최초 호출 포함 (3 = 최초 1 + 재시도 2)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        initialWait.toMillis(), 2.0, 0.5))   // 500ms → 1000ms, 지터 ±50%
                .retryOnException(TRANSIENT)
                .build();
        this.retry = Retry.of(NAME, retryConfig);
        this.circuitBreaker = CircuitBreaker.of(NAME, circuitBreakerConfig);
        this.metrics = metrics;
        metrics.bindCircuitState(circuitBreaker::getState);
    }

    /** 설계서 3.10절 값. 단, 느린 호출 판정은 끈다(아래 주석). */
    static CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // 측정 시간에 RateLimiter 대기(동시 요청 시 수 초)와 재시도 간격이 포함되므로
                // 3초 기준 느린 호출 판정은 정상 상황에서도 OPEN을 유발한다 → 사실상 끈다.
                .slowCallRateThreshold(100)
                .slowCallDurationThreshold(Duration.ofSeconds(60))
                .recordException(TRANSIENT)
                .build();
    }

    public <T> T call(Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, supplier));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            metrics.recordCircuitRejected();
            throw new TetrIoApiException("TETR.IO 호출 차단 중 (CircuitBreaker OPEN)", e, false);
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}