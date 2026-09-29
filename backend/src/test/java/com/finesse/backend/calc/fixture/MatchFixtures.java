package com.finesse.backend.calc.fixture;

import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.PseudonymId;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 MatchHistory 생성 도우미. 실제 닉네임·원본 데이터는 사용하지 않는다.
 */
public final class MatchFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();
    public static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    private MatchFixtures() {}

    /** 본인 APM/PPS/VS만 지정 (FancyMathCalculator 테스트용) */
    public static MatchHistory match(double apm, double pps, double vs) {
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), apm, pps, vs, 0, MatchResult.WIN);
    }

    /** matchId, 시각(BASE + 초), 승패만 지정 (승률 계산 테스트용) */
    public static MatchHistory result(String matchId, int secondsFromBase, MatchResult result) {
        return create(matchId, BASE.plusSeconds(secondsFromBase), 60, 1.0, 120, 0, result);
    }

    /** 본인 APM/PPS/VS/TR과 승패 지정 (구간 비교 테스트용) */
    public static MatchHistory stats(double apm, double pps, double vs, double tr, MatchResult result) {
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), apm, pps, vs, tr, result);
    }

    private static MatchHistory create(String matchId, Instant playedAt,
                                       double apm, double pps, double vs, double tr,
                                       MatchResult result) {
        return new MatchHistory(
                matchId, playedAt, PseudonymId.of(0),
                pps, apm, vs,
                0, 0, 0,
                null, null, null, null,
                null, null, null, null,
                tr, 0,
                result,
                List.of()
        );
    }
}