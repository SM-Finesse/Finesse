package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tetrio")
public record TetrioProperties(
        String baseUrl,
        int requestTimeoutSeconds,
        long minRequestIntervalMs,
        int pageLimit,
        int windowMaxMatches,
        int windowMaxDays,
        // 타임아웃 기준 문서(23번) 6.1절 — 재시도 1회로 통일 (기존 파이프라인 3회/백엔드설계 2회 혼재를 정리)
        int retry
) {
}
