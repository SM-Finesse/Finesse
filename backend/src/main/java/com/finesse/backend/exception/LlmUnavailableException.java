package com.finesse.backend.exception;

/**
 * LLM 서버 전체 다운/네트워크 자체 불가 (Finesse-API명세서 8장 → HTTP 503, comment 영역만 실패).
 */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
