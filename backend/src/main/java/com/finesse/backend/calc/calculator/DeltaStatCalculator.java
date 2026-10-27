package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.DeltaStats.StatAverages;
import com.finesse.backend.calc.domain.MatchHistory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 본인과 상대의 스탯 차이 (설계서 7장, 하이라이트 지표 설계 2026-09-30).
 * 모든 Δ는 매치별로 (본인 값 − 상대 값)을 계산한 뒤 산술 평균한다. 양수면 본인 우위.
 * 하이라이트 후보는 ΔAPP·ΔWeighted APP·ΔVS/APM·ΔCheese Index이며, ΔPPS·ΔAPM·ΔVS는 차트용 원시값이다.
 * 플레이스타일 Δ(8장)는 매치마다 본인·상대 플레이스타일을 공식으로 계산해 차이를 평균한다.
 * 플레이스타일을 계산할 수 있는 매치가 전체의 50% 미만이면 4개 모두 null이다.
 * 공격·수비 챕터의 “나 vs 상대 평균” 표시를 위해 같은 매치 집합의 본인·상대 평균(mine·opp)도 함께 반환한다(7.4절, v4.0).
 */
@Component
public class DeltaStatCalculator implements AnalyticsCalculator<DeltaStats> {

    static final double MIN_PPS = FancyFormulas.MIN_PPS;
    static final double MIN_PLAYSTYLE_RATIO = 0.5;

    @Override
    public CalculatorKey key() {
        return CalculatorKey.DELTA;
    }

    @Override
    public DeltaStats calculate(AnalyticsContext context) {
        List<MatchHistory> computable = context.matches().stream()
                .filter(DeltaStatCalculator::isComputable)
                .toList();
        if (computable.isEmpty()) {
            return null;   // 7장(v3.5): 계산 가능한 매치가 없으면 예외 대신 null
        }

        Double[] playstyle = playstyleDeltas(computable);

        return new DeltaStats(
                computable.size(),
                avgDelta(computable, MatchHistory::myPps, MatchHistory::oppPps),
                avgDelta(computable, MatchHistory::myApm, MatchHistory::oppApm),
                avgDelta(computable, MatchHistory::myVs, MatchHistory::oppVs),
                avgDelta(computable,
                        m -> app(m.myApm(), m.myPps()),
                        m -> app(m.oppApm(), m.oppPps())),
                avgDelta(computable,
                        m -> FancyFormulas.weightedAppOf(m.myApm(), m.myPps(), m.myVs()),
                        m -> FancyFormulas.weightedAppOf(m.oppApm(), m.oppPps(), m.oppVs())),
                avgDelta(computable,
                        m -> FancyFormulas.vsApm(m.myVs(), m.myApm()),
                        m -> FancyFormulas.vsApm(m.oppVs(), m.oppApm())),
                avgDelta(computable,
                        m -> FancyFormulas.cheeseIndexOf(m.myApm(), m.myPps(), m.myVs()),
                        m -> FancyFormulas.cheeseIndexOf(m.oppApm(), m.oppPps(), m.oppVs())),
                playstyle[0], playstyle[1], playstyle[2], playstyle[3],
                averages(computable, true),
                averages(computable, false)
        );
    }

    /**
     * 플레이스타일 Δ 4개 [opener, plonk, stride, infDs] (설계서 8장).
     * 본인·상대 모두 계산 가능한 매치만 쓰고, 그런 매치가 전체의 50% 미만이면 모두 null.
     */
    static Double[] playstyleDeltas(List<MatchHistory> matches) {
        double[] sum = new double[4];
        int used = 0;
        for (MatchHistory m : matches) {
            FancyFormulas.Playstyle mine = FancyFormulas.playstyleOf(m.myApm(), m.myPps(), m.myVs());
            FancyFormulas.Playstyle theirs = FancyFormulas.playstyleOf(m.oppApm(), m.oppPps(), m.oppVs());
            if (mine == null || theirs == null) {
                continue;
            }
            sum[0] += mine.opener() - theirs.opener();
            sum[1] += mine.plonk() - theirs.plonk();
            sum[2] += mine.stride() - theirs.stride();
            sum[3] += mine.infDs() - theirs.infDs();
            used++;
        }
        if (used == 0 || used < matches.size() * MIN_PLAYSTYLE_RATIO) {
            return new Double[] {null, null, null, null};
        }
        return new Double[] {sum[0] / used, sum[1] / used, sum[2] / used, sum[3] / used};
    }

    /** 0 나눗셈이 없도록 본인·상대 모두 APM > 0, PPS ≥ 0.1인 매치만 사용한다 (모든 Δ에 같은 기준). */
    static boolean isComputable(MatchHistory m) {
        return m.myApm() > 0.0 && m.myPps() >= MIN_PPS
                && m.oppApm() > 0.0 && m.oppPps() >= MIN_PPS;
    }

    static double avgDelta(List<MatchHistory> matches,
                           ToDoubleFunction<MatchHistory> mine,
                           ToDoubleFunction<MatchHistory> opponent) {
        double sum = 0;
        for (MatchHistory m : matches) {
            sum += mine.applyAsDouble(m) - opponent.applyAsDouble(m);
        }
        return sum / matches.size();
    }

    /**
     * 본인(mine = true) 또는 상대의 매치 평균 (7.4절, v4.0). Δ와 같은 매치 집합을 쓰므로 mine − opp = Δ다.
     */
    static StatAverages averages(List<MatchHistory> matches, boolean mine) {
        double apm = 0, pps = 0, vs = 0, app = 0, wApp = 0, vsApm = 0, cheese = 0;
        for (MatchHistory m : matches) {
            double a = mine ? m.myApm() : m.oppApm();
            double p = mine ? m.myPps() : m.oppPps();
            double v = mine ? m.myVs() : m.oppVs();
            apm += a;
            pps += p;
            vs += v;
            app += app(a, p);
            wApp += FancyFormulas.weightedAppOf(a, p, v);
            vsApm += FancyFormulas.vsApm(v, a);
            cheese += FancyFormulas.cheeseIndexOf(a, p, v);
        }
        int n = matches.size();
        return new StatAverages(apm / n, pps / n, vs / n, app / n, wApp / n, vsApm / n, cheese / n);
    }

    /** APP = apm / (pps × 60) (설계서 6.2절) */
    static double app(double apm, double pps) {
        return FancyFormulas.app(apm, pps);
    }
}