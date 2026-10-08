package com.finesse.backend.dto;

/**
 * 백엔드 → LLM 서버, light 스코프 요청 (기능 명세서 3.4절 확정 스키마).
 */
public record LlmLightRequest(StatsResponse.FixedMetrics fixedMetrics, StatsResponse.DeltaMetrics deltaMetrics) {
}
