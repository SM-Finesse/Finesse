package com.finesse.backend.calc.collector;

import java.util.List;

/**
 * 매치 수집 결과 (설계서 3.4·3.7절).
 * matches = 1년 이내 최신 최대 300판, 최신순. 300판이 모이면 더 이상 페이지를 호출하지 않는다(3.4절, v3.10 — 이전 구간 수집 폐지).
 */
public record CollectionResult(
        List<RawMatch> matches,
        CollectionStatus status,
        int requestedPages,
        int successfulPages,
        int failedPages,
        int droppedRecords,
        boolean reachedCutoff
) {
    public CollectionResult {
        matches = List.copyOf(matches);
    }

    public boolean partial() {
        return status == CollectionStatus.PARTIAL;
    }

    public boolean analyzable() {
        return status == CollectionStatus.COMPLETE || status == CollectionStatus.PARTIAL;
    }
}