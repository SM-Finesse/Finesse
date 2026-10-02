package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 트래픽이 몰릴 때 stats 수집(캐시 미스) 동시 처리 상한 — 타임아웃 기준 문서(23번) 5.4절 "503 BUSY"의 1단계 구현.
 * TETR.IO 호출은 서버 전체에서 1초에 1번이고 유저 1명이 최대 9번(대표 업적 포함) 부르므로, 동시 2명이면 약 18초로
 * stats 20초 마감 안에 들고 3명부터는 넘친다 → 넘칠 요청은 줄 세우지 않고 바로 503 BUSY.
 * 대기열(차례 기다리기)은 다음 단계에서 넣는다.
 */
@ConfigurationProperties(prefix = "app.stats")
public record StatsLoadProperties(
        int maxConcurrentCollections,
        int busyRetryAfterSeconds // 503 응답의 Retry-After 헤더 값
) {
}
