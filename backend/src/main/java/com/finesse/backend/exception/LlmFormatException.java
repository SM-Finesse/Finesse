package com.finesse.backend.exception;

/**
 * light 스코프에서 3회 재시도 후에도 형식 오류가 지속될 때 (기능 명세서 3.3절 → HTTP 502).
 * heavy는 챕터 단위 실패라 이 예외를 쓰지 않고 status="failed"로 대체한다 (4.2-1절).
 */
public class LlmFormatException extends RuntimeException {
    public LlmFormatException(String message) {
        super(message);
    }
}
