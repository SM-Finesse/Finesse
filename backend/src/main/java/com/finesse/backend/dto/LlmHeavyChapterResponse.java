package com.finesse.backend.dto;

/**
 * LLM 서버 → 백엔드, 챕터 1건당 응답 (Finesse-API명세서 4.2-1절).
 */
public record LlmHeavyChapterResponse(String chapterId, String footnote) {
}
