package com.finesse.backend.exception;

/**
 * light가 엔드포인트 마감(40초)에 걸려 더 시도하지 못하고 끝났을 때 → HTTP 504 LLM_TIMEOUT
 * (QA 라이트 뷰 2차 제안, 10/12 — 형식 오류 502와 원인을 구분). 응답을 하나도 못 받은 경우(서버 무응답)는 503 그대로.
 */
public class LlmTimeoutException extends RuntimeException {
    public LlmTimeoutException(String message) {
        super(message);
    }
}
