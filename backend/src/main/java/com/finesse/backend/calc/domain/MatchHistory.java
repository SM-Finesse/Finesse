package com.finesse.backend.calc.domain;

import java.time.Instant;
import java.util.List;

public record MatchHistory(
        String matchId,
        Instant playedAt,
        PseudonymId opponentId,
        double myPps, double myApm, double myVs,
        double oppPps, double oppApm, double oppVs,
        Double myStatrankOpener, Double myStatrankPlonk,
        Double myStatrankStride, Double myStatrankInfDs,
        Double oppStatrankOpener, Double oppStatrankPlonk,
        Double oppStatrankStride, Double oppStatrankInfDs,
        double myTr, double oppTr,
        MatchResult result,
        List<MatchRound> rounds
) {
    public MatchHistory {
        rounds = List.copyOf(rounds);
    }

    public boolean isWin() {
        return result == MatchResult.WIN;
    }
}