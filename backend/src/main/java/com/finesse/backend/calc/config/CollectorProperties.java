package com.finesse.backend.calc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finesse.analytics.collector")
public record CollectorProperties(
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