package com.finesse.backend.exception;

import com.finesse.backend.dto.QueueTicketResponse;

/**
 * 결과 대신 대기표를 준다 — stats·comment(light)는 GlobalExceptionHandler가 202 Accepted로,
 * heavy는 CommentService가 200 스트림 안 {@code event: queued}로 보낸다(「전적 검색 대기열 문서」 5.1절).
 * 오류가 아니라 정상 흐름이지만, 요청 스레드에서 결과 타입(StatsResponse 등) 대신 돌려주기 위해 예외로 올린다.
 */
public class QueuedException extends RuntimeException {

    private final transient QueueTicketResponse ticket;

    public QueuedException(QueueTicketResponse ticket) {
        super("queued " + ticket.stage() + " " + ticket.ticketId(), null, false, false);
        this.ticket = ticket;
    }

    public QueueTicketResponse ticket() {
        return ticket;
    }
}
