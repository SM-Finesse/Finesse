package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.FancyStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.exception.AnalyticsErrorCode;
import com.finesse.backend.calc.exception.InsufficientMatchException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 본인의 APM/PPS/VS 원값만으로 계산하는 순수 수학 지표 (설계서 6장).
 * 매치별로 APP → VS/APM → DS/S → DS/P → Cheese → GbE → Weighted APP 순서로 계산한 뒤 산술 평균한다.
 */
@Component
public class FancyMathCalculator implements AnalyticsCalculator<FancyStats> {

    static final double MIN_PPS = FancyFormulas.MIN_PPS;

    @Override
    public CalculatorKey key() {
        return CalculatorKey.FANCY;
    }

    @Override
    public FancyStats calculate(AnalyticsContext context) {
        List<MatchHistory> computable = context.matches().stream()
                .filter(FancyMathCalculator::isComputable)
                .toList();
        if (computable.isEmpty()) {
            throw new InsufficientMatchException(AnalyticsErrorCode.ANALYTICS_NO_VALID_MATCH, 0, 1);
        }

        double sumApm = 0, sumPps = 0, sumVs = 0;
        double sumApp = 0, sumVsApm = 0, sumDsS = 0, sumCheese = 0, sumGbE = 0, sumWeightedApp = 0;

        for (MatchHistory m : computable) {
            double apm = m.myApm();
            double pps = m.myPps();
            double vs = m.myVs();

            double app = app(apm, pps);
            double vsApm = vsApm(vs, apm);
            double dsS = dsS(vs, apm);
            double dsP = dsP(dsS, pps);
            double cheese = cheeseIndex(dsP, vsApm, app);
            double gbE = gbE(app, dsS, pps);
            double weightedApp = weightedApp(app, cheese);

            sumApm += apm;
            sumPps += pps;
            sumVs += vs;
            sumApp += app;
            sumVsApm += vsApm;
            sumDsS += dsS;
            sumCheese += cheese;
            sumGbE += gbE;
            sumWeightedApp += weightedApp;
        }

        int n = computable.size();
        return new FancyStats(
                n,
                sumApm / n, sumPps / n, sumVs / n,
                sumApp / n, sumVsApm / n, sumDsS / n,
                sumCheese / n, sumGbE / n, sumWeightedApp / n
        );
    }

    /** APM=0이면 APP·VS/APM이 0 나눗셈, PPS<0.1이면 비정상 매치 (설계서 6.4절). */
    static boolean isComputable(MatchHistory m) {
        return m.myApm() > 0.0 && m.myPps() >= MIN_PPS;
    }

    // 공식은 FancyFormulas에 있다 (DeltaStatCalculator와 공유).

    static double app(double apm, double pps) {
        return FancyFormulas.app(apm, pps);
    }

    static double vsApm(double vs, double apm) {
        return FancyFormulas.vsApm(vs, apm);
    }

    static double dsS(double vs, double apm) {
        return FancyFormulas.dsS(vs, apm);
    }

    static double dsP(double dsS, double pps) {
        return FancyFormulas.dsP(dsS, pps);
    }

    static double cheeseIndex(double dsP, double vsApm, double app) {
        return FancyFormulas.cheeseIndex(dsP, vsApm, app);
    }

    static double gbE(double app, double dsS, double pps) {
        return FancyFormulas.gbE(app, dsS, pps);
    }

    static double weightedApp(double app, double cheese) {
        return FancyFormulas.weightedApp(app, cheese);
    }
}