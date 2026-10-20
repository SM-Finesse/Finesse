package com.finesse.backend.calc.domain;

/**
 * 매치의 라운드 1개 (설계서 4.2절).
 *
 * @param myPpsAtRound 라운드 본인 PPS, 응답에 없으면 null — 라운드 곡선(11.13절)에만 쓴다 (v3.6)
 */
public record MatchRound(
        int roundIndex,
        double myVsAtRound,
        double oppVsAtRound,
        Double trGapAtRound,
        boolean wonRound,
        Double myPpsAtRound
) {}
