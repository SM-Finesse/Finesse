package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchRound;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 하이라이트 지표 계산 (설계서 10~11장).
 * 입력 순서와 무관하게 같은 결과가 나오도록 모든 계산 전에 최신순으로 정렬한다.
 */
@Component
public class HighlightStatCalculator implements AnalyticsCalculator<HighlightStats> {

    static final double TR_TREND_RATIO = 0.1;               // 11.1절
    static final int QUINTILES = 5;                         // 11.2절
    static final int COMEBACK_ROUND_GAP = 2;                // 11.3절: 2판 이상 열세/우세

    /** 11.6절 tie-breaker: TR Gap(상대 − 본인) 내림차순 → playedAt 내림차순 → matchId 오름차순 */
    static final Comparator<MatchHistory> BY_TR_GAP_DESC =
            Comparator.comparingDouble(HighlightStatCalculator::trGap).reversed()
                    .thenComparing(MatchHistory::playedAt, Comparator.reverseOrder())
                    .thenComparing(MatchHistory::matchId);

    @Override
    public CalculatorKey key() {
        return CalculatorKey.HIGHLIGHT;
    }

    @Override
    public HighlightStats calculate(AnalyticsContext context) {
        List<MatchHistory> matches = context.matches().stream()
                .sorted(MatchOrdering.NEWEST_FIRST)
                .toList();

        Double trTrend = trTrendDelta(matches);
        Double strengthSplit = strengthSplit(matches);

        List<MatchHistory> comebackOpp = matches.stream()
                .filter(m -> hadRoundGap(m, false)).toList();
        int comebackWon = (int) comebackOpp.stream().filter(MatchHistory::isWin).count();
        Double comebackRate = comebackOpp.isEmpty()
                ? null : (double) comebackWon / comebackOpp.size();

        List<MatchHistory> leadOpp = matches.stream()
                .filter(m -> hadRoundGap(m, true)).toList();
        int comebackAgainstAllowed = (int) leadOpp.stream().filter(m -> !m.isWin()).count();
        Double comebackRateAgainst = leadOpp.isEmpty()
                ? null : (double) comebackAgainstAllowed / leadOpp.size();

        // 하이라이트 지표 설계(2026-09-30): 둘 중 하나라도 분모 0이면 null
        Double deltaComeback = comebackRate == null || comebackRateAgainst == null
                ? null : comebackRate - comebackRateAgainst;

        double vsSlope = sessionVsSlope(matches);

        boolean eligible = trTrend != null || strengthSplit != null
                || comebackRate != null || comebackRateAgainst != null;

        return new HighlightStats(
                trTrend, strengthSplit,
                comebackOpp.size(), comebackWon, comebackRate,
                leadOpp.size(), comebackAgainstAllowed, comebackRateAgainst,
                deltaComeback,
                vsSlope, eligible
        );
    }

    // ── 11.1 TR Trend Delta ────────────────────────────────────────────

    /**
     * 최근 N판 평균 TR − 전체 평균 TR, N = ceil(TR 있는 판수 × 0.1). matches는 최신순.
     * 매치 당시 TR이 없는 매치는 제외하고 계산한다.
     */
    static Double trTrendDelta(List<MatchHistory> newestFirst) {
        List<MatchHistory> withTr = newestFirst.stream().filter(m -> m.myTr() != null).toList();
        if (withTr.isEmpty()) {
            return null;
        }
        int n = (int) Math.ceil(withTr.size() * TR_TREND_RATIO);
        return avgTr(withTr.subList(0, n)) - avgTr(withTr);
    }

    private static double avgTr(List<MatchHistory> matches) {
        double sum = 0;
        for (MatchHistory m : matches) {
            sum += m.myTr();
        }
        return sum / matches.size();
    }

    // ── 11.2 / 11.6 / 11.7 strength_split ─────────────────────────────

    /**
     * TR Gap = 상대 TR − 본인 TR (매치 당시 기준). 내림차순 정렬 시 Q1 = 가장 강한 상대 구간,
     * Q5 = 가장 약한 상대 구간이 되어 strength_split(Q1 − Q5)은 보통 음수가 된다.
     */
    static double trGap(MatchHistory m) {
        return m.oppTr() - m.myTr();
    }

    /**
     * Q1(강한 상대) 승률 − Q5(약한 상대) 승률.
     * 본인·상대 TR이 모두 있는 매치만 사용하며, 그런 매치가 5판 미만이면 null.
     */
    static Double strengthSplit(List<MatchHistory> matches) {
        List<MatchHistory> withTr = matches.stream()
                .filter(m -> m.myTr() != null && m.oppTr() != null)
                .toList();
        if (withTr.size() < QUINTILES) {
            return null;
        }
        List<MatchHistory> sorted = withTr.stream().sorted(BY_TR_GAP_DESC).toList();
        List<QuintileBounds> bounds = computeQuintileBounds(sorted.size());
        return winRate(slice(sorted, bounds.get(0))) - winRate(slice(sorted, bounds.get(QUINTILES - 1)));
    }

    record QuintileBounds(int quintileIndex, int size, int startOffset) {}

    /** 몫은 total / 5, 나머지는 1분위부터 1개씩 배분 (11.7절). */
    static List<QuintileBounds> computeQuintileBounds(int totalMatches) {
        int base = totalMatches / QUINTILES;
        int remainder = totalMatches % QUINTILES;
        List<QuintileBounds> bounds = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < QUINTILES; i++) {
            int size = base + (i < remainder ? 1 : 0);
            bounds.add(new QuintileBounds(i, size, offset));
            offset += size;
        }
        return bounds;
    }

    private static List<MatchHistory> slice(List<MatchHistory> sorted, QuintileBounds b) {
        return sorted.subList(b.startOffset(), b.startOffset() + b.size());
    }

    private static double winRate(List<MatchHistory> matches) {
        long wins = matches.stream().filter(MatchHistory::isWin).count();
        return (double) wins / matches.size();
    }

    // ── 11.3 comeback_rate / 11.3부 comeback_rate_against ─────────────

    /**
     * 라운드를 roundIndex 순으로 진행하며, 어떤 라운드가 시작되기 직전
     * 라운드 스코어 차이가 2 이상이었는지 확인한다.
     * lead=false → 내가 2판 이상 열세(역전 기회), lead=true → 내가 2판 이상 우세(역전 허용 기회).
     */
    static boolean hadRoundGap(MatchHistory match, boolean lead) {
        List<MatchRound> rounds = match.rounds().stream()
                .sorted(Comparator.comparingInt(MatchRound::roundIndex))
                .toList();
        int myWins = 0;
        int oppWins = 0;
        for (MatchRound round : rounds) {
            int gap = lead ? myWins - oppWins : oppWins - myWins;
            if (gap >= COMEBACK_ROUND_GAP) {
                return true;
            }
            if (round.wonRound()) {
                myWins++;
            } else {
                oppWins++;
            }
        }
        return false;
    }

    // ── 11.4 / 11.5 session_vs_slope ──────────────────────────────────

    /**
     * 전체 매치의 라운드를 roundIndex별로 모아 평균 VS를 구하고(x = roundIndex, y = 평균 VS),
     * OLS 기울기를 계산한다. 서로 다른 roundIndex가 2개 미만이거나 분모가 0이면 0.0.
     */
    static double sessionVsSlope(List<MatchHistory> matches) {
        Map<Integer, double[]> sumAndCount = new TreeMap<>();
        for (MatchHistory m : matches) {
            for (MatchRound r : m.rounds()) {
                double[] acc = sumAndCount.computeIfAbsent(r.roundIndex(), k -> new double[2]);
                acc[0] += r.myVsAtRound();
                acc[1] += 1;
            }
        }
        int n = sumAndCount.size();
        if (n < 2) {
            return 0.0;
        }

        double[] xs = new double[n];
        double[] ys = new double[n];
        int i = 0;
        for (Map.Entry<Integer, double[]> e : sumAndCount.entrySet()) {
            xs[i] = e.getKey();
            ys[i] = e.getValue()[0] / e.getValue()[1];
            i++;
        }
        return olsSlope(xs, ys);
    }

    static double olsSlope(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 2) {
            return 0.0;
        }
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += xs[i];
            meanY += ys[i];
        }
        meanX /= n;
        meanY /= n;

        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            numerator += dx * (ys[i] - meanY);
            denominator += dx * dx;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }
}