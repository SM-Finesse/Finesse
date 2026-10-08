package com.finesse.backend.exception;

/**
 * TETR.IO API 다운/타임아웃 시 발생 (FR-01, Finesse-API명세서 8장 → HTTP 502).
 */
public class TetrioApiException extends RuntimeException {
    public TetrioApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
