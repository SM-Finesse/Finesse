package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.exception.AnalyticsErrorCode;
import com.finesse.backend.calc.exception.InsufficientMatchException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 본인과 상대의 스탯 차이 (설계서 7장, 하이라이트 지표 설계 2026-09-30).
 * 모든 Δ는 매치별로 (본인 값 − 상대 값)을 계산한 뒤 산술 평균한다. 양수면 본인 우위.
 * 하이라이트 후보는 ΔAPP·ΔWeighted APP·ΔVS/APM·ΔCheese Index이며, ΔPPS·ΔAPM·ΔVS는 차트용 원시값이다.
 * 플레이스타일 Δ(8장)는 StatrankCurve의 데이터 출처·형식이 확정되면 구현하며, 그 전까지 null을 반환한다.
 */
@Component
public class DeltaStatCalculator implements AnalyticsCalculator<DeltaStats> {

    static final double MIN_PPS = FancyFormulas.MIN_PPS;

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
            throw new InsufficientMatchException(AnalyticsErrorCode.ANALYTICS_NO_VALID_MATCH, 0, 1);
        }

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
                null, null, null, null // 8장 플레이스타일 Δ — StatrankCurve 연동 후 구현
        );
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

    /** APP = apm / (pps × 60) (설계서 6.2절) */
    static double app(double apm, double pps) {
        return FancyFormulas.app(apm, pps);
    }
}