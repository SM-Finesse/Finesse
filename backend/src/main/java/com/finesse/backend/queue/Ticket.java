package com.finesse.backend.queue;

/**
 * 대기표 하나 — 요청한 사람마다 따로(「전적 검색 대기열 문서」 3.6절).
 * 결과 상태(done·failed·error_code)는 대기표마다 따로 들고 있어서, 작업이 끝나 정리돼도 늦게 조회한 대기표가 결과를 받는다.
 * <p>
 * 요청 안에서 기다리는 요청도 작업의 대기표 목록에 넣는다 — 이때는 아직 발급(issued) 전이라 대기표 맵에 없고,
 * 6초를 넘기는 등으로 202로 바뀔 때 {@link TicketRegistry#publish}로 발급된다.
 */
public final class Ticket {

    final String id;
    final String stage;
    final String ip;
    final QueueJob job;

    private volatile boolean finished;
    private volatile boolean succeeded;
    private volatile String errorCode;
    volatile long lastSeenNanos;
    volatile long finishedNanos;
    volatile boolean issued;
    volatile boolean reserved; // IP 자리를 잡아 둠 — 발급 때 TicketRegistry가 푼다

    Ticket(String id, String stage, String ip, QueueJob job, long nowNanos) {
        this.id = id;
        this.stage = stage;
        this.ip = ip;
        this.job = job;
        this.lastSeenNanos = nowNanos;
    }

    public String id() {
        return id;
    }

    public String stage() {
        return stage;
    }

    public QueueJob job() {
        return job;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean succeeded() {
        return succeeded;
    }

    public String errorCode() {
        return errorCode;
    }

    /** 작업이 끝났을 때 작업이 부른다 — 성공이면 done, 실패면 failed + 원래 냈을 오류 코드 */
    public void finish(boolean ok, String code, long nowNanos) {
        this.succeeded = ok;
        this.errorCode = ok ? null : code;
        this.finishedNanos = nowNanos;
        this.finished = true;
    }
}
