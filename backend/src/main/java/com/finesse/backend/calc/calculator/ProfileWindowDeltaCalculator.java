package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 프로필 패널 5칸(TR·WR·APM·PPS·VS)의 "직전 구간 대비 변화율 %" (설계서 11.11절).
 * 현재 구간 = context.matches(), 이전 구간 = context.previousWindowMatches().
 * 11.1절 tr_trend_delta와는 별개 계산이다.
 */
@Component
public class ProfileWindowDeltaCalculator implements AnalyticsCalculator<ProfileWindowDeltaStats> {

    @Override
    public CalculatorKey key() {
        return CalculatorKey.PROFILE_DELTA;
    }

    @Override
    public ProfileWindowDeltaStats calculate(AnalyticsContext context) {
        List<MatchHistory> current = context.matches();
        List<MatchHistory> previous = context.previousWindowMatches();
        if (current.isEmpty() || previous.isEmpty()) {
            return ProfileWindowDeltaStats.unavailable();
        }

        return new ProfileWindowDeltaStats(
                trDeltaPct(current, previous),
                deltaPct(winRatePct(current), winRatePct(previous)),
                deltaPct(avg(current, MatchHistory::myApm), avg(previous, MatchHistory::myApm)),
                deltaPct(avg(current, MatchHistory::myPps), avg(previous, MatchHistory::myPps)),
                deltaPct(avg(current, MatchHistory::myVs), avg(previous, MatchHistory::myVs))
        );
    }

    /** 매치 당시 TR이 있는 매치만 평균한다. 어느 한 구간에 TR이 하나도 없으면 null. */
    static Double trDeltaPct(List<MatchHistory> current, List<MatchHistory> previous) {
        List<MatchHistory> cur = current.stream().filter(m -> m.myTr() != null).toList();
        List<MatchHistory> prev = previous.stream().filter(m -> m.myTr() != null).toList();
        if (cur.isEmpty() || prev.isEmpty()) {
            return null;
        }
        return deltaPct(avg(cur, MatchHistory::myTr), avg(prev, MatchHistory::myTr));
    }

    static double avg(List<MatchHistory> matches, ToDoubleFunction<MatchHistory> field) {
        double sum = 0;
        for (MatchHistory m : matches) {
            sum += field.applyAsDouble(m);
        }
        return sum / matches.size();
    }

    /** 구간 승률(%) = 승수 / 구간 매치 수 × 100 */
    static double winRatePct(List<MatchHistory> matches) {
        long wins = matches.stream().filter(MatchHistory::isWin).count();
        return wins * 100.0 / matches.size();
    }

    /** 이전 값이 0이면(예: 이전 구간 승률 0%) 분모 0을 막고 0.0으로 처리한다. */
    static double deltaPct(double current, double previous) {
        return previous == 0.0 ? 0.0 : (current - previous) / previous * 100.0;
    }
}