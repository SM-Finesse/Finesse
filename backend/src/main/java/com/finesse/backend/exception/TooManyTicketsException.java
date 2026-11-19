package com.finesse.backend.exception;

/** 한 IP의 대기표가 상한(app.queue.max-tickets-per-ip)을 넘음 — 429 TOO_MANY_TICKETS (「전적 검색 대기열 문서」 5.3·5.5절) */
public class TooManyTicketsException extends RuntimeException {

    public static final String MESSAGE = "이미 기다리는 검색이 있습니다";

    public TooManyTicketsException() {
        super(MESSAGE, null, false, false);
    }
}
