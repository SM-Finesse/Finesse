package com.finesse.backend.calc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finesse.analytics")
public record AnalyticsProperties(
        int coldStartThreshold,
        int maxMatchWindow,
        int recentWinLossWindow
) {}