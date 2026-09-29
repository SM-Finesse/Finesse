package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 엔드포인트(요청 하나를 끝내야 하는 마감) 레벨 타임아웃 — 타임아웃 기준 문서(23번, v0.3) 9절.
 * "호출 1회" 타임아웃({@link LlmProperties}, {@link TetrioProperties})과는 다른 레이어다:
 * 엔드포인트는 재시도까지 포함한 요청 전체의 최악 경로 상한이고, 항상 안쪽(호출 1회 × 시도횟수)보다 길다.
 */
@ConfigurationProperties(prefix = "app.endpoint")
public record EndpointProperties(
        int statsSeconds,
        int lightSeconds,
        // 헤비 8챕터 전체 처리 상한 — LLM 서버 대수에 따라 아래 중 하나를 CommentService가 자동 선택
        int heavySeconds,
        int heavySingleServerSeconds
) {
}
