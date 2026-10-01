package com.finesse.backend.client;

import org.springframework.stereotype.Component;

/**
 * TETR.IO API 이용 규칙(법적 검토 1절) — 초당 1회 수준 유지.
 * 백엔드 프로세스 전체에서 공유하는 단순 스로틀러 (요청 큐/딜레이 방식, Finesse-API명세서 4.1-1절).
 *
 * ※ 임시 구현 — 데이터팀(위성훈, data-eng 브랜치)의 calc 모듈이 완성되면 그쪽 수집 로직으로
 * 교체 예정 (2026-09-29 팀 확인).
 */
@Component("backendTetrioRateLimiter") // calc.collector.RateLimiter와 빈 이름이 겹치지 않게
public class RateLimiter {

    private final Object lock = new Object();
    private long nextAllowedAtMillis = 0L;

    public void await(long minIntervalMs) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long waitMs = nextAllowedAtMillis - now;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Rate limiter interrupted", e);
                }
            }
            nextAllowedAtMillis = Math.max(now, System.currentTimeMillis()) + minIntervalMs;
        }
    }
}
