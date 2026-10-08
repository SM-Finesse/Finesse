package com.finesse.backend.dto;

/**
 * 공통 에러 응답 포맷 (Finesse-API명세서 3장 확정): { "error_code": "...", "message": "..." }
 */
public record ApiErrorResponse(String errorCode, String message, Integer retryAfterSeconds) {

    /** retry_after_seconds는 SERVER_BUSY일 때만 — 그 외에는 null이라 응답에서 생략된다 */
    public ApiErrorResponse(String errorCode, String message) {
        this(errorCode, message, null);
    }
}
