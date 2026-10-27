package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchRound;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 신뢰도 판정(「지표 신뢰도 판정 기준」)에 쓰는 매치 단위 표본.
 * 지표 값과 판정 근거가 어긋나지 않도록 Calculator와 같은 매치 집합·같은 공식을 쓴다.
 * AnalyticsCalculator 구현체가 아니며 계산 결과를 바꾸지 않는다. calc 내부 전용이다.
 */
public final class MetricSamples {

    /** Δ 8종 지표 키 (하이라이트 후보 1~8번) */
    public static final List<String> DELTA_METRICS = List.of(
            "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
            "delta_app", "delta_weighted_app", "delta_vs_apm", "delta_cheese_index");

    private MetricSamples() {}

    /** 매치별 본인 값과 상대 값 (같은 인덱스가 같은 매치). 매치별 Δ = mine − opp */
    public record Paired(double[] mine, double[] opp) {
        public int size() {
            return mine.length;
        }

        public double[] deltas() {
            double[] d = new double[mine.length];
            for (int i = 0; i < d.length; i++) {
                d[i] = mine[i] - opp[i];
            }
            return d;
        }
    }

    /**
     * Δ 지표 하나의 매치별 값 (7·8장). 매치 집합은 DeltaStatCalculator와 같다 —
     * 본인·상대 모두 APM > 0, PPS ≥ 0.1인 매치, 플레이스타일은 그중 양쪽 모두 계산 가능한 매치.
     */
    public static Paired deltaSeries(String key, List<MatchHistory> matches) {
        List<MatchHistory> computable = matches.stream().filter(DeltaStatCalculator::isComputable).toList();
        return switch (key) {
            case "delta_app" -> paired(computable,
                    m -> FancyFormulas.app(m.myApm(), m.myPps()),
                    m -> FancyFormulas.app(m.oppApm(), m.oppPps()));
            case "delta_weighted_app" -> paired(computable,
                    m -> FancyFormulas.weightedAppOf(m.myApm(), m.myPps(), m.myVs()),
                    m -> FancyFormulas.weightedAppOf(m.oppApm(), m.oppPps(), m.oppVs()));
            case "delta_vs_apm" -> paired(computable,
                    m -> FancyFormulas.vsApm(m.myVs(), m.myApm()),
                    m -> FancyFormulas.vsApm(m.oppVs(), m.oppApm()));
            case "delta_cheese_index" -> paired(computable,
                    m -> FancyFormulas.cheeseIndexOf(m.myApm(), m.myPps(), m.myVs()),
                    m -> FancyFormulas.cheeseIndexOf(m.oppApm(), m.oppPps(), m.oppVs()));
            case "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds" -> playstyle(key, computable);
            default -> throw new IllegalArgumentException("Δ 지표가 아닙니다: " + key);
        };
    }

    private static Paired paired(List<MatchHistory> matches,
                                 ToDoubleFunction<MatchHistory> mine, ToDoubleFunction<MatchHistory> opp) {
        double[] a = new double[matches.size()];
        double[] b = new double[matches.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = mine.applyAsDouble(matches.get(i));
            b[i] = opp.applyAsDouble(matches.get(i));
        }
        return new Paired(a, b);
    }

    private static Paired playstyle(String key, List<MatchHistory> computable) {
        List<double[]> rows = new ArrayList<>();
        for (MatchHistory m : computable) {
            FancyFormulas.Playstyle mine = FancyFormulas.playstyleOf(m.myApm(), m.myPps(), m.myVs());
            FancyFormulas.Playstyle theirs = FancyFormulas.playstyleOf(m.oppApm(), m.oppPps(), m.oppVs());
            if (mine == null || theirs == null) {
                continue;   // 8.3절: 계산 불가 매치는 플레이스타일에서만 뺀다
            }
            rows.add(new double[] {pick(key, mine), pick(key, theirs)});
        }
        double[] a = new double[rows.size()];
        double[] b = new double[rows.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = rows.get(i)[0];
            b[i] = rows.get(i)[1];
        }
        return new Paired(a, b);
    }

    private static double pick(String key, FancyFormulas.Playstyle p) {
        return switch (key) {
            case "delta_opener" -> p.opener();
            case "delta_plonk" -> p.plonk();
            case "delta_stride" -> p.stride();
            default -> p.infDs();
        };
    }

    /** strength_split의 Q1(가장 약한 상대)·Q5(가장 강한 상대) 매치. TR 있는 매치가 5판 미만이면 둘 다 빈 목록 */
    public record StrengthSlices(List<MatchHistory> q1, List<MatchHistory> q5) {}

    /** 11.2·11.6·11.7절과 같은 정렬·분위 배분으로 Q1·Q5를 나눈다 */
    public static StrengthSlices strengthSlices(List<MatchHistory> matches) {
        List<MatchHistory> withTr = matches.stream()
                .filter(m -> m.myTr() != null && m.oppTr() != null)
                .sorted(HighlightStatCalculator.BY_TR_GAP_DESC)
                .toList();
        if (withTr.size() < HighlightStatCalculator.QUINTILES) {
            return new StrengthSlices(List.of(), List.of());
        }
        List<HighlightStatCalculator.QuintileBounds> bounds =
                HighlightStatCalculator.computeQuintileBounds(withTr.size());
        HighlightStatCalculator.QuintileBounds first = bounds.get(0);
        HighlightStatCalculator.QuintileBounds last = bounds.get(bounds.size() - 1);
        return new StrengthSlices(
                withTr.subList(first.startOffset(), first.startOffset() + first.size()),
                withTr.subList(last.startOffset(), last.startOffset() + last.size()));
    }

    /** 한 매치에서 session_vs_slope에 들어가는 라운드 (순번, 본인 VS). 조기 종료 매치는 마지막 라운드를 뺐다 */
    public record RoundSeries(int[] roundIndex, double[] vs) {}

    /** 11.4절과 같은 규칙으로 매치별 라운드를 뽑는다 */
    public static List<RoundSeries> roundSeries(List<MatchHistory> matches) {
        List<RoundSeries> out = new ArrayList<>(matches.size());
        for (MatchHistory m : matches) {
            int lastIndex = m.rounds().stream().mapToInt(MatchRound::roundIndex).max().orElse(-1);
            List<MatchRound> used = m.rounds().stream()
                    .filter(r -> !(m.endedEarly() && r.roundIndex() == lastIndex))
                    .toList();
            int[] idx = new int[used.size()];
            double[] vs = new double[used.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = used.get(i).roundIndex();
                vs[i] = used.get(i).myVsAtRound();
            }
            out.add(new RoundSeries(idx, vs));
        }
        return out;
    }

    /** 서로 다른 라운드 순번 수 */
    public static int distinctRoundIndices(List<RoundSeries> sample) {
        java.util.BitSet seen = new java.util.BitSet();
        for (RoundSeries s : sample) {
            for (int i : s.roundIndex()) {
                seen.set(i);
            }
        }
        return seen.cardinality();
    }

    /**
     * 라운드 순번별 평균 VS의 OLS 기울기 — HighlightStatCalculator.sessionVsSlope와 같은 값이다.
     * 부트스트랩에서 2,000번 부르므로 배열로 모은다. 정의 불가면 null.
     */
    public static Double slopeOf(List<RoundSeries> sample) {
        int max = -1;
        for (RoundSeries s : sample) {
            for (int i : s.roundIndex()) {
                max = Math.max(max, i);
            }
        }
        if (max < 0) {
            return null;
        }
        double[] sum = new double[max + 1];
        int[] count = new int[max + 1];
        for (RoundSeries s : sample) {
            int[] idx = s.roundIndex();
            double[] vs = s.vs();
            for (int i = 0; i < idx.length; i++) {
                sum[idx[i]] += vs[i];
                count[idx[i]]++;
            }
        }
        int n = 0;
        for (int c : count) {
            if (c > 0) {
                n++;
            }
        }
        if (n < 2) {
            return null;
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        int k = 0;
        for (int i = 0; i <= max; i++) {
            if (count[i] > 0) {
                xs[k] = i;
                ys[k] = sum[i] / count[i];
                k++;
            }
        }
        return HighlightStatCalculator.olsSlope(xs, ys);
    }
}