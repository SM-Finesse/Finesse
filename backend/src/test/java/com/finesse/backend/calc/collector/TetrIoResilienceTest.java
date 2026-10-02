package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoUserNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TetrIoResilienceTest {

    /** 최근 4회 중 50% 이상 실패하면 OPEN, OPEN은 5분 유지 */
    private static CircuitBreakerConfig smallBreaker() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .recordException(e -> e instanceof TetrIoApiException t && t.retryable())
                .build();
    }

    private static TetrIoResilience resilience(int maxAttempts) {
        return new TetrIoResilience(maxAttempts, Duration.ofMillis(1), smallBreaker());
    }

    @Test
    void 일시적_장애는_재시도해서_성공하면_결과를_돌려준다() {
        AtomicInteger attempts = new AtomicInteger();

        String result = resilience(3).call(() -> {
            if (attempts.incrementAndGet() < 3) throw new TetrIoApiException("일시 장애", null, true);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 재시도를_다_써도_실패하면_마지막_예외를_던진다() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience(3).call(() -> {
            attempts.incrementAndGet();
            throw new TetrIoApiException("일시 장애", null, true);
        })).isInstanceOf(TetrIoApiException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 재시도_대상이_아닌_실패와_404는_한_번만_호출한다() {
        AtomicInteger permanent = new AtomicInteger();
        AtomicInteger notFound = new AtomicInteger();
        TetrIoResilience r = resilience(3);

        assertThatThrownBy(() -> r.call(() -> {
            permanent.incrementAndGet();
            throw new TetrIoApiException("4xx");
        })).isInstanceOf(TetrIoApiException.class);
        assertThatThrownBy(() -> r.call(() -> {
            notFound.incrementAndGet();
            throw new TetrIoUserNotFoundException("user");
        })).isInstanceOf(TetrIoUserNotFoundException.class);

        assertThat(permanent).hasValue(1);
        assertThat(notFound).hasValue(1);
    }

    @Test
    void 일시적_장애가_이어지면_OPEN이_되고_이후_호출은_실행하지_않는다() {
        TetrIoResilience r = resilience(1);
        for (int i = 0; i < 4; i++) {
            try {
                r.call(() -> { throw new TetrIoApiException("일시 장애", null, true); });
            } catch (TetrIoApiException ignored) {
            }
        }
        AtomicInteger called = new AtomicInteger();

        assertThat(r.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> r.call(() -> called.incrementAndGet()))
                .isInstanceOfSatisfying(TetrIoApiException.class, e -> assertThat(e.retryable()).isFalse());
        assertThat(called).hasValue(0);
    }

    @Test
    void 재시도_대상이_아닌_실패는_OPEN을_만들지_않는다() {
        TetrIoResilience r = resilience(1);
        for (int i = 0; i < 4; i++) {
            try {
                r.call(() -> { throw new TetrIoApiException("4xx"); });
            } catch (TetrIoApiException ignored) {
            }
        }

        assertThat(r.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}