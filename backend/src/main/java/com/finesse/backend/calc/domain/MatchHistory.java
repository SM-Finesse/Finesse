package com.finesse.backend.calc.domain;

import java.time.Instant;
import java.util.List;

/**
 * 정제·가명처리를 마친 매치 1건 (설계서 4.2절).
 *
 * @param firstTo    경기 형식(선승 수 3·5·7), 판단 불가면 null (5.11절)
 * @param endedEarly 선승 수에 도달하기 전에 끝난 매치(중간 이탈 추정) (5.11절)
 */
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
        Double myTr, Double oppTr,
        MatchResult result,
        List<MatchRound> rounds,
        Integer firstTo,
        boolean endedEarly
) {
    public MatchHistory {
        rounds = List.copyOf(rounds);
    }

    public boolean isWin() {
        return result == MatchResult.WIN;
    }

    /** 역전 지표(11.3절)에 쓸 수 있는 매치 — 형식을 알고 조기 종료가 아니다 (5.11절). */
    public boolean isComebackEligible() {
        return firstTo != null && !endedEarly;
    }
}