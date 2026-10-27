package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.HighlightStats.StrengthQuintile;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchRound;
import org.springframework.beans.factory.annotation.Autowired;
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

    static final int TR_TREND_MIN_N = 3;                    // 11.1절: N 하한
    static final int TR_TREND_MAX_N = 30;                   // 11.1절: N 상한
    static final int QUINTILES = 5;                         // 11.2절


    private final double trTrendRatio;

    @Autowired
    public HighlightStatCalculator(AnalyticsProperties properties) {
        this.trTrendRatio = properties.trTrendRatio();
    }

    /** trTrendRatio 기본값(0.3)으로 만드는 생성자 */
    public HighlightStatCalculator() {
        this.trTrendRatio = AnalyticsProperties.DEFAULT_TR_TREND_RATIO;
    }

    /** 11.6절 tie-breaker: TR Gap(본인 − 상대) 내림차순 → playedAt 내림차순 → matchId 오름차순 */
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

        Double trTrend = trTrendDelta(matches, trTrendRatio);
        List<StrengthQuintile> quintiles = strengthQuintiles(matches);
        Double strengthSplit = strengthSplit(quintiles);

        List<MatchHistory> comebackOpp = matches.stream()
                .filter(MatchHistory::isComebackEligible)
                .filter(m -> hadRoundGap(m, false, comebackGap(m.firstTo()))).toList();
        int comebackWon = (int) comebackOpp.stream().filter(MatchHistory::isWin).count();
        Double comebackRate = comebackOpp.isEmpty()
                ? null : (double) comebackWon / comebackOpp.size();

        List<MatchHistory> leadOpp = matches.stream()
                .filter(MatchHistory::isComebackEligible)
                .filter(m -> hadRoundGap(m, true, comebackGap(m.firstTo()))).toList();   // v3.9: 역전 기회와 같은 형식별 점수 차
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
                vsSlope, eligible,
                quintiles
        );
    }

    // ── 11.1 TR Trend Delta ────────────────────────────────────────────

    /**
     * 최근 N판 평균 TR − 전체 평균 TR. matches는 최신순.
     * N = clamp(ceil(TR 있는 판수 × ratio), 3, 30)이며, TR 있는 판수보다 클 수 없다.
     * 매치 당시 TR이 없는 매치는 제외하고 계산한다.
     */
    static Double trTrendDelta(List<MatchHistory> newestFirst, double ratio) {
        List<MatchHistory> withTr = newestFirst.stream().filter(m -> m.myTr() != null).toList();
        if (withTr.isEmpty()) {
            return null;
        }
        return avgTr(withTr.subList(0, trTrendN(withTr.size(), ratio))) - avgTr(withTr);
    }

    static int trTrendN(int matchesWithTr, double ratio) {
        int n = (int) Math.ceil(matchesWithTr * ratio);
        n = Math.max(TR_TREND_MIN_N, Math.min(TR_TREND_MAX_N, n));
        return Math.min(n, matchesWithTr);
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
     * TR Gap = 본인 TR − 상대 TR (매치 당시 기준). 내림차순 정렬 시 Q1 = 가장 약한 상대 구간,
     * Q5 = 가장 강한 상대 구간이 되어 strength_split(Q5 − Q1)은 보통 음수가 된다 (11.2절, v3.4).
     */
    static double trGap(MatchHistory m) {
        return m.myTr() - m.oppTr();
    }

    /**
     * 분위별 판수·승수·승률 (Q1 → Q5). 헤비뷰 “상대 강도별 승률” 차트용 (11.2절, v3.8).
     * 본인·상대 TR이 모두 있는 매치만 사용하며, 그런 매치가 5판 미만이면 빈 목록.
     */
    static List<StrengthQuintile> strengthQuintiles(List<MatchHistory> matches) {
        List<MatchHistory> withTr = matches.stream()
                .filter(m -> m.myTr() != null && m.oppTr() != null)
                .toList();
        if (withTr.size() < QUINTILES) {
            return List.of();
        }
        List<MatchHistory> sorted = withTr.stream().sorted(BY_TR_GAP_DESC).toList();
        List<StrengthQuintile> result = new ArrayList<>();
        for (QuintileBounds b : computeQuintileBounds(sorted.size())) {
            List<MatchHistory> slice = slice(sorted, b);
            int wins = (int) slice.stream().filter(MatchHistory::isWin).count();
            result.add(new StrengthQuintile(b.quintileIndex() + 1, slice.size(), wins, (double) wins / slice.size()));
        }
        return result;
    }

    /** Q5(강한 상대) 승률 − Q1(약한 상대) 승률. 분위가 없으면(TR 있는 매치 5판 미만) null. */
    static Double strengthSplit(List<StrengthQuintile> quintiles) {
        if (quintiles.isEmpty()) {
            return null;
        }
        return quintiles.get(QUINTILES - 1).winRate() - quintiles.get(0).winRate();
    }

    record QuintileBounds(int quintileIndex, int size, int startOffset) {}

    /** 몫은 total / 5, 나머지는 가장 강한 상대 구간(Q5)부터 Q4, Q3 순으로 1개씩 배분 (11.7절). */
    static List<QuintileBounds> computeQuintileBounds(int totalMatches) {
        int base = totalMatches / QUINTILES;
        int remainder = totalMatches % QUINTILES;
        List<QuintileBounds> bounds = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < QUINTILES; i++) {
            int size = base + (i >= QUINTILES - remainder ? 1 : 0);
            bounds.add(new QuintileBounds(i, size, offset));
            offset += size;
        }
        return bounds;
    }

    private static List<MatchHistory> slice(List<MatchHistory> sorted, QuintileBounds b) {
        return sorted.subList(b.startOffset(), b.startOffset() + b.size());
    }


    // ── 11.3 comeback_rate / 11.3부 comeback_rate_against ─────────────

    /**
     * 역전 기회 판정 점수 차: 3선승(최대 5판) 2, 5선승(9판) 3, 7선승(13판) 4.
     * 경기 형식(firstTo)은 전처리에서 정한다(5.11절). 형식을 모르거나 조기 종료된 매치는 역전 지표에서 뺀다.
     */
    static int comebackGap(int firstTo) {
        return (firstTo + 1) / 2;
    }

    /**
     * 라운드를 roundIndex 순으로 진행하며, 어떤 라운드가 시작되기 직전
     * 라운드 스코어 차이가 gap 이상이었는지 확인한다.
     * lead=false → 내가 gap 이상 열세(역전 기회), lead=true → 내가 gap 이상 우세(역전 허용 기회).
     */
    static boolean hadRoundGap(MatchHistory match, boolean lead, int gap) {
        List<MatchRound> rounds = match.rounds().stream()
                .sorted(Comparator.comparingInt(MatchRound::roundIndex))
                .toList();
        int myWins = 0;
        int oppWins = 0;
        for (MatchRound round : rounds) {
            int diff = lead ? myWins - oppWins : oppWins - myWins;
            if (diff >= gap) {
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
     * 조기 종료 매치는 마지막 라운드를 빼고 모은다.
     * OLS 기울기를 계산한다. 서로 다른 roundIndex가 2개 미만이거나 분모가 0이면 0.0.
     */
    static double sessionVsSlope(List<MatchHistory> matches) {
        Map<Integer, double[]> sumAndCount = new TreeMap<>();
        for (MatchHistory m : matches) {
            int lastIndex = m.rounds().stream().mapToInt(MatchRound::roundIndex).max().orElse(-1);
            for (MatchRound r : m.rounds()) {
                if (m.endedEarly() && r.roundIndex() == lastIndex) {
                    continue;   // 이탈이 일어난 라운드의 VS는 중간에 끊긴 값 (11.4절)
                }
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