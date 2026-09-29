package com.finesse.backend.model;

import java.time.Instant;
import java.util.List;

/**
 * TETR.IO 원시 레코드 1건을 정규화한 결과 — 데이터 수집 명세 5장 산출물 형태를 그대로 따른다.
 * tr_before/tr_after 등 TR 관련 필드는 extras.league 원소가 null인 매치(6.2절)에서 null일 수 있다.
 */
public record NormalizedMatch(
        String id,
        Instant ts,
        String result, // "victory" | "defeat"
        Side me,
        Side opp,
        List<RoundSample> rounds
) {
    public record Side(
            String id,
            int wins,
            Double apm,
            Double pps,
            Double vs, // vsscore
            Double trBefore,
            Double trAfter,
            String rank,
            String usernameCurrent,   // opp에서만 사용 (otherusers[].username, 6.3절)
            String usernameAtMatch    // opp에서만 사용 (leaderboard[].username, 6.3절)
    ) {
        public boolean hasTr() {
            return trBefore != null && trAfter != null;
        }
    }
}
