package com.finesse.backend.calc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "finesse.analytics.collector")
public record CollectorProperties(
        String baseUrl,                  // https://ch.tetr.io/api
        Duration minRequestInterval,     // 1s — 약 1 request/sec (3.6절)
        int maxPages,
        int maxMatchesPerPage,
        int maxAgeDays,
        int minPartialMatches,
        int maxRetryAttempts,
        Duration requestTimeout,
        Duration sessionRefreshTimeout
) {
    public int maxTotalMatches() {
        return maxPages * maxMatchesPerPage; // 3 × 100 = 300
    }
}
