package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.MatchHistory;

import java.util.Comparator;

/**
 * Calculator들이 공통으로 쓰는 결정론적 정렬 기준 (설계서 1.1절 ②).
 * Calculator끼리 직접 참조하지 않도록(20장) 별도 유틸로 둔다.
 */
final class MatchOrdering {

    /** 최신순, 같은 시각이면 matchId 오름차순 */
    static final Comparator<MatchHistory> NEWEST_FIRST =
            Comparator.comparing(MatchHistory::playedAt, Comparator.reverseOrder())
                    .thenComparing(MatchHistory::matchId);

    private MatchOrdering() {}
}