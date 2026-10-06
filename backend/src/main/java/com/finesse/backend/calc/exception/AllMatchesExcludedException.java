package com.finesse.backend.calc.exception;

/**
 * 수집한 매치가 모두 정제 단계에서 제외됨 (설계서 5.10절, 49.2절 14번).
 * Facade는 Cold Start Bypass와 같은 경로로 처리한다.
 */
public class AllMatchesExcludedException extends RuntimeException {

    private final int totalCount;

    public AllMatchesExcludedException(int totalCount) {
        super("수집한 매치 " + totalCount + "판이 모두 정제 단계에서 제외됨");
        this.totalCount = totalCount;
    }

    public int totalCount() {
        return totalCount;
    }
}