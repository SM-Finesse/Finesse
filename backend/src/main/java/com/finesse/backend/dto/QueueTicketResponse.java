package com.finesse.backend.dto;

/**
 * 대기표 — 「전적 검색 대기열 문서」 5.1·5.2절.
 * <ul>
 *   <li>202 본문(stats·comment light)과 heavy SSE {@code event: queued}의 data: queued · ticket_id · stage · position · estimated_wait_seconds · poll_after_seconds</li>
 *   <li>GET /api/v1/queue/{ticket_id}: status · stage · position · estimated_wait_seconds · poll_after_seconds · error_code</li>
 * </ul>
 * 값이 없는 칸은 응답에서 빠진다(non_null).
 *
 * @param status   waiting(줄에 있음) · running(수집·코멘트 생성 중) · done(결과 준비됨) · failed(실패)
 * @param stage    stats(수집) · comment(라이트 코멘트) · heavy(헤비 코멘트)
 * @param position waiting일 때만 — 내 앞 작업 수(진행 중 포함) + 1. 화면의 "앞에 N명"은 position − 1
 */
public record QueueTicketResponse(
        Boolean queued,
        String ticketId,
        String status,
        String stage,
        Integer position,
        Integer estimatedWaitSeconds,
        int pollAfterSeconds,
        String errorCode) {

    public static final String STATUS_WAITING = "waiting";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public static final String STAGE_STATS = "stats";
    public static final String STAGE_COMMENT = "comment";
    public static final String STAGE_HEAVY = "heavy";

    /** 202 · event: queued 본문 */
    public static QueueTicketResponse issued(String ticketId, String stage, Integer position,
                                             Integer estimatedWaitSeconds, int pollAfterSeconds) {
        return new QueueTicketResponse(true, ticketId, null, stage, position, estimatedWaitSeconds, pollAfterSeconds, null);
    }

    /** GET /api/v1/queue/{ticket_id} 본문 */
    public static QueueTicketResponse state(String status, String stage, Integer position,
                                            Integer estimatedWaitSeconds, int pollAfterSeconds, String errorCode) {
        return new QueueTicketResponse(null, null, status, stage, position, estimatedWaitSeconds, pollAfterSeconds, errorCode);
    }
}
