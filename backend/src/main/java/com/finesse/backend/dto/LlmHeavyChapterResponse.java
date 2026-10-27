package com.finesse.backend.dto;

/**
 * LLM 서버 → 백엔드, 챕터 1건당 응답 (Finesse-API명세서 4.2-1절).
 * fallback: LLM 문장이 검사를 통과하지 못해(또는 시간 예산이 모자라) 서버가 data 값으로 만든 대체 문장이거나,
 * rivals처럼 처음부터 서버 문장을 쓰는 챕터면 true (LLM 헤비 v4.3, 10/20). 예전 서버는 보내지 않아 null.
 */
public record LlmHeavyChapterResponse(String chapterId, String footnote, Boolean fallback) {
}
