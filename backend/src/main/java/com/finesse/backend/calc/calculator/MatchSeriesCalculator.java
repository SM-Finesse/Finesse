package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchRound;
import com.finesse.backend.calc.domain.MatchSeriesStats;
import com.finesse.backend.calc.domain.MatchSeriesStats.RoundPoint;
import com.finesse.backend.calc.domain.MatchSeriesStats.TrPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 경기별 시계열 (설계서 11.13절, v3.6).
 * TR 시계열은 라이트뷰 TR 추이 카드, 라운드 곡선은 헤비뷰 컨디션 챕터에 쓴다.
 */
@Component
public class MatchSeriesCalculator implements AnalyticsCalculator<MatchSeriesStats> {

    /** 오래된 매치 → 최근 매치, 같은 시각이면 matchId 오름차순 */
    static final Comparator<MatchHistory> OLDEST_FIRST =
            Comparator.comparing(MatchHistory::playedAt).thenComparing(MatchHistory::matchId);

    @Override
    public CalculatorKey key() {
        return CalculatorKey.SERIES;
    }

    @Override
    public MatchSeriesStats calculate(AnalyticsContext context) {
        return new MatchSeriesStats(trSeries(context.matches()), roundCurve(context.matches()));
    }

    /** 매치 시작 시점 본인 TR. TR이 없는 매치는 뺀다. */
    static List<TrPoint> trSeries(List<MatchHistory> matches) {
        return matches.stream()
                .filter(m -> m.myTr() != null)
                .sorted(OLDEST_FIRST)
                .map(m -> new TrPoint(m.playedAt(), m.myTr()))
                .toList();
    }

    /**
     * 라운드 순서별 평균 PPS·VS. 조기 종료 매치의 마지막 라운드는 뺀다(11.4절과 같은 기준).
     * PPS가 없는 라운드는 PPS 평균에서만 뺀다.
     */
    static List<RoundPoint> roundCurve(List<MatchHistory> matches) {
        Map<Integer, double[]> acc = new TreeMap<>();   // [vs 합, 라운드 수, pps 합, pps 수]
        for (MatchHistory m : matches) {
            int lastIndex = m.rounds().stream().mapToInt(MatchRound::roundIndex).max().orElse(-1);
            for (MatchRound r : m.rounds()) {
                if (m.endedEarly() && r.roundIndex() == lastIndex) {
                    continue;
                }
                double[] a = acc.computeIfAbsent(r.roundIndex(), k -> new double[4]);
                a[0] += r.myVsAtRound();
                a[1] += 1;
                if (r.myPpsAtRound() != null) {
                    a[2] += r.myPpsAtRound();
                    a[3] += 1;
                }
            }
        }
        List<RoundPoint> curve = new ArrayList<>();
        acc.forEach((index, a) -> curve.add(new RoundPoint(
                index + 1,
                a[3] == 0 ? null : a[2] / a[3],
                a[0] / a[1],
                (int) a[1])));
        return curve;
    }
}
