package com.finesse.backend.queue;

/**
 * 대기표가 바라보는 작업 — 「전적 검색 대기열 문서」 3.6절.
 * 작업은 유저(와 단계)마다 하나이고, 같은 작업을 여러 대기표가 함께 바라본다.
 * 끝나면 작업이 자기를 바라보는 대기표마다 {@link Ticket#finish}를 부른다.
 */
public interface QueueJob {

    /** stats · comment · heavy */
    String stage();

    /** 수집·생성이 시작됐으면 true(대기표 status running), 아직 줄에 있으면 false(waiting) */
    boolean isRunning();

    /** waiting일 때 — 내 앞 작업 수(진행 중 포함) + 1 */
    int position();

    /** 예상 대기(초) — waiting이면 시작까지, running이면 끝날 때까지 */
    int estimatedWaitSeconds();

    /**
     * 대기표 하나가 떠남(취소·만료). 바라보는 대기표가 하나도 없고 아직 시작 전이면 작업도 줄에서 뺀다 —
     * 떠난 사람 때문에 TETR.IO·LLM 호출을 쓰지 않도록(3.5·3.6절). 이미 시작했으면 끝까지 한다.
     */
    void detach(Ticket ticket);
}
