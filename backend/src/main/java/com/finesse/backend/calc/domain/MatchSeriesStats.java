package com.finesse.backend.calc.domain;

import java.time.Instant;
import java.util.List;

/**
 * 경기별 시계열 (설계서 11.13절, v3.6).
 *
 * @param trSeries   매치 시작 시점 본인 TR, 오래된 매치 → 최근 매치 순. TR 없는 매치는 빠진다
 * @param roundCurve 라운드 순서별 본인 평균 PPS·VS, round 오름차순
 */
public record MatchSeriesStats(
        List<TrPoint> trSeries,
        List<RoundPoint> roundCurve
) {
    public MatchSeriesStats {
        trSeries = List.copyOf(trSeries);
        roundCurve = List.copyOf(roundCurve);
    }

    /** TR 추이 카드의 점 하나 */
    public record TrPoint(Instant playedAt, double tr) {}

    /**
     * 라운드 곡선의 점 하나.
     *
     * @param round   1부터 시작하는 라운드 순서
     * @param avgPps  그 순서 라운드들의 평균 PPS, PPS가 하나도 없으면 null
     * @param avgVs   그 순서 라운드들의 평균 VS
     * @param samples 그 순서의 라운드 수 — 값이 작을수록 흔들린다
     */
    public record RoundPoint(int round, Double avgPps, double avgVs, int samples) {}
}
