package com.finesse.backend.calc.fixture;

import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchRound;
import com.finesse.backend.calc.domain.PseudonymId;

import java.time.Instant;
import java.util.ArrayList;
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
        return create("test-match-" + n, BASE.plusSeconds(n), 0, apm, pps, vs, 60, 1.0, 120, 0.0, 0.0, MatchResult.WIN, List.of());
    }

    /** matchId, 시각(BASE + 초), 승패만 지정 (승률 계산 테스트용) */
    public static MatchHistory result(String matchId, int secondsFromBase, MatchResult result) {
        return create(matchId, BASE.plusSeconds(secondsFromBase), 0, 60, 1.0, 120, 60, 1.0, 120, 0.0, 0.0, result, List.of());
    }

    /** 본인 APM/PPS/VS/TR과 승패 지정 (구간 비교 테스트용) */
    public static MatchHistory stats(double apm, double pps, double vs, double tr, MatchResult result) {
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), 0, apm, pps, vs, 60, 1.0, 120, tr, 0.0, result, List.of());
    }

    /** 시각, 본인/상대 TR(매치 당시), 승패 지정 (TR Trend·strength_split 테스트용) */
    public static MatchHistory tr(int secondsFromBase, double myTr, double oppTr, MatchResult result) {
        return create("tr-" + secondsFromBase, BASE.plusSeconds(secondsFromBase), 0,
                60, 1.0, 120, 60, 1.0, 120, myTr, oppTr, result, List.of());
    }

    /** 매치 당시 TR이 없는 매치 (TR 결측 테스트용) */
    public static MatchHistory withoutTr(int secondsFromBase, MatchResult result) {
        return create("no-tr-" + secondsFromBase, BASE.plusSeconds(secondsFromBase), 0,
                60, 1.0, 120, 60, 1.0, 120, null, null, result, List.of());
    }

    /** 최종 승패와 라운드별 승리 여부를 순서대로 지정 (comeback 테스트용) */
    public static MatchHistory rounds(MatchResult result, boolean... roundWins) {
        List<MatchRound> rounds = new ArrayList<>();
        for (int i = 0; i < roundWins.length; i++) {
            rounds.add(new MatchRound(i, 0, 0, null, roundWins[i]));
        }
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), 0, 60, 1.0, 120, 60, 1.0, 120, 0.0, 0.0, result, rounds);
    }

    /** 라운드별 본인 VS를 순서대로 지정 (session_vs_slope 테스트용) */
    public static MatchHistory vsRounds(double... myVsPerRound) {
        List<MatchRound> rounds = new ArrayList<>();
        for (int i = 0; i < myVsPerRound.length; i++) {
            rounds.add(new MatchRound(i, myVsPerRound[i], 0, null, true));
        }
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), 0, 60, 1.0, 120, 60, 1.0, 120, 0.0, 0.0, MatchResult.WIN, rounds);
    }

    /** 본인·상대 APM/PPS/VS 지정 (DeltaStatCalculator 테스트용) */
    public static MatchHistory delta(double myApm, double myPps, double myVs,
                                     double oppApm, double oppPps, double oppVs) {
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), 0,
                myApm, myPps, myVs, oppApm, oppPps, oppVs, 0.0, 0.0, MatchResult.WIN, List.of());
    }

    /** 상대 시퀀스(PseudonymId.of)와 승패 지정 (RivalryCalculator 테스트용) */
    public static MatchHistory against(int opponentSeq, MatchResult result) {
        int n = SEQ.getAndIncrement();
        return create("test-match-" + n, BASE.plusSeconds(n), opponentSeq,
                60, 1.0, 120, 60, 1.0, 120, 0.0, 0.0, result, List.of());
    }

    private static MatchHistory create(String matchId, Instant playedAt, int opponentSeq,
                                       double myApm, double myPps, double myVs,
                                       double oppApm, double oppPps, double oppVs,
                                       Double myTr, Double oppTr,
                                       MatchResult result, List<MatchRound> rounds) {
        return new MatchHistory(
                matchId, playedAt, PseudonymId.of(opponentSeq),
                myPps, myApm, myVs,
                oppPps, oppApm, oppVs,
                null, null, null, null,
                null, null, null, null,
                myTr, oppTr,
                result,
                rounds
        );
    }
}