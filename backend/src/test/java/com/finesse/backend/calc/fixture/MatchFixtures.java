package com.finesse.backend.calc.fixture;

import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.PseudonymId;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class MatchFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    private MatchFixtures() {}

    /** 본인 APM/PPS/VS만 지정하고 나머지는 기본값인 매치 */
    public static MatchHistory match(double apm, double pps, double vs) {
        int n = SEQ.getAndIncrement();
        return new MatchHistory(
                "test-match-" + n, BASE.plusSeconds(n), PseudonymId.of(0),
                pps, apm, vs,
                0, 0, 0,
                null, null, null, null,
                null, null, null, null,
                0, 0,
                MatchResult.WIN,
                List.of()
        );
    }
}