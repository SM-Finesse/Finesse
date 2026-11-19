package com.finesse.backend.exception;

/** 없는(만료된·서버 재시작으로 사라진) 대기표 — 404 TICKET_NOT_FOUND (「전적 검색 대기열 문서」 5.2·5.3절) */
public class TicketNotFoundException extends RuntimeException {

    public static final String MESSAGE = "대기 시간이 지났습니다, 다시 검색해 주세요";

    public TicketNotFoundException() {
        super(MESSAGE, null, false, false);
    }
}
