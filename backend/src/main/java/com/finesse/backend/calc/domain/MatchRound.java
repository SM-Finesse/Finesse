package com.finesse.backend.calc.domain;

public record MatchRound(
        int roundIndex,
        double myVsAtRound,
        double oppVsAtRound,
        double trGapAtRound,
        boolean wonRound
) {}