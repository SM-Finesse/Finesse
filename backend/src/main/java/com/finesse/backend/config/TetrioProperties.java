package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 백엔드가 직접 하는 TETR.IO 호출(GET /users/{username}, 프로필 패널용)에만 쓰는 설정.
 * 매치 수집 범위(300판·1년·페이지 크기)와 호출 간격(RateLimiter)은 calc 모듈의 finesse.analytics.collector가 담당한다.
 */
@ConfigurationProperties(prefix = "tetrio")
public record TetrioProperties(
        String baseUrl,
        int requestTimeoutSeconds,
        int retry // 재시도 횟수 — 최초 포함 retry+1회 시도
) {
}
