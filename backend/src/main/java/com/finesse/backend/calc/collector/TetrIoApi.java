package com.finesse.backend.calc.collector;

/**
 * TETR.IO API 호출 계약. 실제 구현은 TetrIoApiClient이며, 테스트에서는 가짜 구현으로 대체한다.
 */
public interface TetrIoApi {

    /** @throws com.finesse.backend.calc.exception.TetrIoUserNotFoundException 존재하지 않는 유저(HTTP 404) */
    UserSummary fetchLeagueSummary(String username, String sessionId);

    /** afterCursor가 null이면 첫 페이지 */
    RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit);
}