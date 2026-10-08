package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import org.springframework.stereotype.Component;

/**
 * 프로세스 전체에서 TETR.IO 호출 간격을 minRequestInterval 이상으로 유지한다 (설계서 3.6절, 약 1 request/sec).
 */
@Component
public class RateLimiter {

    private final long minIntervalNanos;
    private long nextAllowedAt = Long.MIN_VALUE;

    public RateLimiter(CollectorProperties properties) {
        this.minIntervalNanos = properties.minRequestInterval().toNanos();
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        if (nextAllowedAt != Long.MIN_VALUE && now < nextAllowedAt) {
            sleepNanos(nextAllowedAt - now);
            now = System.nanoTime();
        }
        nextAllowedAt = now + minIntervalNanos;
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RateLimiter 대기 중 인터럽트", e);
        }
    }
}