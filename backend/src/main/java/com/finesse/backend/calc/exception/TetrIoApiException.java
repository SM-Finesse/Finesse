package com.finesse.backend.calc.exception;

/**
 * TETR.IO API 호출 실패 — 페이지 단위 실패로 처리한다 (설계서 3.7절).
 * retryable은 일시적 장애(네트워크·타임아웃·5xx)인지 여부이며, Retry·CircuitBreaker가 이 값으로 판단한다 (3.9·3.10절).
 */
public class TetrIoApiException extends RuntimeException {

    private final boolean retryable;

    public TetrIoApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public TetrIoApiException(String message) {
        this(message, null, false);
    }

    public boolean retryable() {
        return retryable;
    }
}