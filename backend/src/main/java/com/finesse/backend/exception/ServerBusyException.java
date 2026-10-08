package com.finesse.backend.exception;

/**
 * stats 수집 동시 처리 상한을 넘은 요청 — 503 SERVER_BUSY (타임아웃 기준 문서 23번 5.4절).
 */
public class ServerBusyException extends RuntimeException {

    private final int retryAfterSeconds;

    public ServerBusyException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
