package com.finesse.backend.calc.collector;

import java.util.List;

/**
 * 매치 수집 결과 (설계서 3.4·3.7절).
 * matches = 현재 구간(1년 이내 최신 최대 300판), previousWindowMatches = 그 다음 구간(최대 300판, 11.11절).
 * 둘 다 최신순이다.
 */
public record CollectionResult(
        List<RawMatch> matches,
        List<RawMatch> previousWindowMatches,
        CollectionStatus status,
        int requestedPages,
        int successfulPages,
        int failedPages,
        int droppedRecords,
        boolean reachedCutoff
) {
    public CollectionResult {
        matches = List.copyOf(matches);
        previousWindowMatches = List.copyOf(previousWindowMatches);
    }

    public boolean partial() {
        return status == CollectionStatus.PARTIAL;
    }

    public boolean analyzable() {
        return status == CollectionStatus.COMPLETE || status == CollectionStatus.PARTIAL;
    }
}