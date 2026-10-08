package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 프로필 패널 5칸(TR·WR·APM·PPS·VS)의 "최근 구간 대비 변화율 %" (설계서 11.11절, v3.10 재구현).
 * 분석 매치(최대 300판)를 최신순으로 놓고 최근 N판과 나머지를 비교한다.
 * N = clamp(ceil(분석 판수 × trTrendRatio), 3, 30) — 11.1절 tr_trend_delta와 같은 비율 설정.
 * 11.1절 tr_trend_delta와는 별개 계산이다(기준 판수와 단위가 다르다).
 */
@Component
public class ProfileWindowDeltaCalculator implements AnalyticsCalculator<ProfileWindowDeltaStats> {

    static final int MIN_N = 3;
    static final int MAX_N = 30;

    private final double ratio;

    @Autowired
    public ProfileWindowDeltaCalculator(AnalyticsProperties properties) {
        this.ratio = properties.trTrendRatio();
    }

    /** trTrendRatio 기본값(0.3)으로 만드는 생성자 */
    public ProfileWindowDeltaCalculator() {
        this.ratio = AnalyticsProperties.DEFAULT_TR_TREND_RATIO;
    }

    @Override
    public CalculatorKey key() {
        return CalculatorKey.PROFILE_DELTA;
    }

    @Override
    public ProfileWindowDeltaStats calculate(AnalyticsContext context) {
        List<MatchHistory> newestFirst = context.matches().stream()
                .sorted(MatchOrdering.NEWEST_FIRST)
                .toList();
        int n = recentN(newestFirst.size(), ratio);
        if (n >= newestFirst.size()) {
            return ProfileWindowDeltaStats.unavailable();   // 나머지 구간이 비면 비교할 수 없다
        }
        List<MatchHistory> recent = newestFirst.subList(0, n);
        List<MatchHistory> rest = newestFirst.subList(n, newestFirst.size());

        return new ProfileWindowDeltaStats(
                trDeltaPct(recent, rest),
                deltaPct(winRatePct(recent), winRatePct(rest)),
                deltaPct(avg(recent, MatchHistory::myApm), avg(rest, MatchHistory::myApm)),
                deltaPct(avg(recent, MatchHistory::myPps), avg(rest, MatchHistory::myPps)),
                deltaPct(avg(recent, MatchHistory::myVs), avg(rest, MatchHistory::myVs))
        );
    }

    /** 최근 구간 판수 N = clamp(ceil(판수 × ratio), 3, 30). 판수가 0이면 0. */
    static int recentN(int matches, double ratio) {
        if (matches == 0) {
            return 0;
        }
        int n = (int) Math.ceil(matches * ratio);
        return Math.max(MIN_N, Math.min(MAX_N, n));
    }

    /** 매치 당시 TR이 있는 매치만 평균한다. 어느 한 구간에 TR이 하나도 없으면 null. */
    static Double trDeltaPct(List<MatchHistory> recent, List<MatchHistory> rest) {
        List<MatchHistory> cur = recent.stream().filter(m -> m.myTr() != null).toList();
        List<MatchHistory> prev = rest.stream().filter(m -> m.myTr() != null).toList();
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

    /** 나머지 구간 값이 0이면(예: 나머지 구간 승률 0%) 분모 0을 막고 0.0으로 처리한다. */
    static double deltaPct(double recent, double rest) {
        return rest == 0.0 ? 0.0 : (recent - rest) / rest * 100.0;
    }
}