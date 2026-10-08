package com.finesse.backend.calc.domain;

public record MatchRound(
        int roundIndex,
        double myVsAtRound,
        double oppVsAtRound,
        Double trGapAtRound,
        boolean wonRound
) {}