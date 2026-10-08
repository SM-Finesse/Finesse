package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 트래픽이 몰릴 때 stats 수집(캐시 미스) 동시 처리 상한과 대기열 — 타임아웃 기준 문서(23번) 5.4절 "503 BUSY".
 * TETR.IO 호출은 서버 전체에서 1초에 1번이고 유저 1명이 최대 8번 부르므로, 동시 2명이면 약 16초로
 * stats 20초 마감 안에 들고 3명부터는 넘친다.
 * 1단계(10/2): 넘치면 바로 503. 2단계: 자리가 없으면 maxQueueWaitSeconds까지 차례를 기다리고(도착 순서대로),
 * 이미 maxWaiting명이 기다리고 있으면 기다려도 그 안에 차례가 오기 어려우므로 바로 503.
 * maxQueueWaitSeconds가 0이면 1단계처럼 기다리지 않는다.
 */
@ConfigurationProperties(prefix = "app.stats")
public record StatsLoadProperties(
        int maxConcurrentCollections,
        int busyRetryAfterSeconds, // 503 응답의 Retry-After 헤더 값
        int maxQueueWaitSeconds,   // 자리를 기다리는 최대 시간 — 23번 5.4절 "대기 6초 초과는 503"
        int maxWaiting             // 동시에 기다릴 수 있는 요청 수 — 넘으면 기다리지 않고 바로 503
) {

    // 생성자가 둘이라 설정 값을 어느 쪽으로 넣을지 지정한다 (calc AnalyticsProperties와 같은 방식)
    @ConstructorBinding
    public StatsLoadProperties {
    }

    /** 대기열 없이 1단계(바로 503)로 동작하는 설정 */
    public StatsLoadProperties(int maxConcurrentCollections, int busyRetryAfterSeconds) {
        this(maxConcurrentCollections, busyRetryAfterSeconds, 0, 0);
    }
}
