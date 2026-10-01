package com.finesse.backend.calc.preprocessing;

/**
 * 매치 정제 결과 (설계서 5.7·5.8절).
 * API가 확정하는 상태(ABORTED·DISCONNECTED·FORFEIT)와 Finesse가 수치로 추론하는 상태
 * (INVALID_STATS·INSUFFICIENT_ROUND_DATA)를 구분한다.
 */
public enum MatchValidity {
    VALID,
    ABORTED,
    DISCONNECTED,
    FORFEIT,
    INVALID_STATS,
    INSUFFICIENT_ROUND_DATA;

    /** API 응답 필드로 확정된 상태인지 (false면 Finesse가 수치 조건으로 추론한 상태) */
    public boolean isReportedByApi() {
        return this == ABORTED || this == DISCONNECTED || this == FORFEIT;
    }
}