package com.finesse.backend.model;

import java.util.List;

/**
 * 수집 계층 → 계산 계층 산출물 (데이터 수집 명세 5장).
 */
public record CollectionResult(NormalizedUser user, Window window, List<NormalizedMatch> matches, Meta meta) {

    public record NormalizedUser(String id, String username, String rank, double tr, double glicko, double rd,
                                  Double gxe, int gamesPlayed) {
    }

    public record Window(int matches, String limitedBy) { // "count" | "period"
    }

    public record Meta(int droppedRecords, int missingTrMatches) {
    }
}
