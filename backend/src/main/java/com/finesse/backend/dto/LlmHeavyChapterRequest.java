package com.finesse.backend.dto;

/**
 * 백엔드 → LLM 서버, 챕터 1건당 요청 (Finesse-API명세서 4.2-1절).
 */
public record LlmHeavyChapterRequest(String chapterId, Object data) {
}
