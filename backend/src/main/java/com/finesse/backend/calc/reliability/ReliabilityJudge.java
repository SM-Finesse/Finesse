package com.finesse.backend.calc.reliability;

import com.finesse.backend.calc.calculator.MetricSamples;
import com.finesse.backend.calc.calculator.MetricSamples.Paired;
import com.finesse.backend.calc.calculator.MetricSamples.RoundSeries;
import com.finesse.backend.calc.calculator.MetricSamples.StrengthSlices;
import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.config.ReliabilityProperties;
import com.finesse.backend.calc.config.ReliabilityProperties.MetricConstants;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MetricCaution;
import com.finesse.backend.calc.domain.MetricReliability;
import com.finesse.backend.calc.domain.PseudonymId;
import com.finesse.backend.calc.domain.ReliabilityReason;
import com.finesse.backend.calc.domain.ReliabilityReport;
import com.finesse.backend.calc.domain.SamplePart;
import com.finesse.backend.calc.domain.StatResult;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 하이라이트 후보 11개의 신뢰도 판정 (「지표 신뢰도 판정 기준」 1장).
 * 두 층으로 판정한다 — 지표 단위 표시(caution, 지표마다 고정)와 유저 단위 라벨 2개(표본 부족·의심 데이터).
 * 어느 층도 매치를 제외하거나 지표 값을 바꾸지 않으며, 결과는 화면 표시 전용이다(LLM 요청·하이라이트 선정에 쓰지 않음).
 * 지표 값이 null이면 그 지표는 판정하지 않는다(결과에 키가 없음).
 */
@Component
public class ReliabilityJudge {

    private static final double ALPHA = 0.05;

    private final ReliabilityProperties properties;

    public ReliabilityJudge(ReliabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * @param result           계산 결과 (delta·highlight를 쓴다)
     * @param matches          지표 계산에 쓴 정제 후 매치 (AnalyticsContext와 같은 목록)
     * @param collectionCapped 수집 레코드가 상한(300건)에 도달했는지 — 도달하면 2단계 표본 부족을 붙이지 않는다(5.2절)
     * @param seedKey          부트스트랩 시드 재료 (조회 유저 이름). 같은 데이터면 같은 결과가 나온다(2.6절)
     */
    public ReliabilityReport judge(StatResult result, List<MatchHistory> matches, boolean collectionCapped,
                                   String seedKey) {
        DeltaStats delta = result.delta();
        HighlightStats highlight = result.highlight();
        Map<String, MetricReliability> out = new LinkedHashMap<>();
        for (String key : HighlightProperties.METRICS) {
            MetricReliability r = switch (key) {
                case "strength_split" -> highlight == null || highlight.strengthSplit() == null
                        ? null : strengthSplit(matches, collectionCapped);
                case "session_vs_slope" -> highlight == null || highlight.sessionVsSlope() == null
                        ? null : sessionVsSlope(highlight.sessionVsSlope(), matches, collectionCapped, seedKey);
                case "delta_comeback" -> highlight == null || highlight.deltaComeback() == null
                        ? null : deltaComeback(highlight);
                default -> deltaValue(key, delta) == null ? null : deltaMetric(key, matches);
            };
            if (r != null) {
                out.put(key, r);
            }
        }
        return new ReliabilityReport(properties.version(), out);
    }

    // ── 지표 단위 표시 (1.2절) ───────────────────────────────────────

    /** R₃₀₀ < 0.5면 신뢰 불가, < 0.9면 해석 주의 */
    MetricCaution caution(String key) {
        double r300 = properties.constants(key).r300();
        if (r300 < properties.unreliableBelow()) {
            return MetricCaution.UNRELIABLE;
        }
        return r300 < properties.targetReliability() ? MetricCaution.INTERPRETATION_CAUTION : MetricCaution.NONE;
    }

    // ── Δ 8종: 차이형 (1.4절, 2.2·2.7·2.10절) ────────────────────────

    private MetricReliability deltaMetric(String key, List<MatchHistory> matches) {
        MetricCaution caution = caution(key);
        Paired series = MetricSamples.deltaSeries(key, matches);
        double[] d = series.deltas();
        int n = d.length;
        Map<SamplePart, Integer> sizes = Map.of(SamplePart.ALL, n);
        if (n == 0) {
            return null;
        }
        double mean = ReliabilityMath.mean(d);
        MetricConstants c = properties.constants(key);

        // 2단계 — 신뢰도 R_i (1단계 없음: 표본이 작으면 SE와 t가 함께 커져 여기서 걸린다)
        Double errorMargin = null;
        Double threshold = null;
        Double reliability = null;
        boolean insufficient;
        if (n < 2) {
            insufficient = true;   // 표준편차를 정할 수 없어 오차를 모른다
        } else {
            double t = ReliabilityMath.t975(n - 1);
            double eObs = t * Math.sqrt(c.vif()) * ReliabilityMath.sampleSd(d) / Math.sqrt(n);
            double se = eObs / t;
            double sigma2 = c.sigmaTrue() * c.sigmaTrue();
            double r = sigma2 + se * se == 0.0 ? 1.0 : sigma2 / (sigma2 + se * se);
            double target = properties.targetReliability();
            errorMargin = eObs;
            threshold = t * c.sigmaTrue() * Math.sqrt((1 - target) / target);
            reliability = r;
            insufficient = r < target;
        }

        // 3·4단계 — 이상치 영향도 (본인 값과 상대 값 각각에서 찾고 합친다)
        Integer outlierCount = null;
        boolean suspicious = false;
        BitSet mine = ReliabilityMath.modifiedZOutliers(series.mine(), properties.outlierThreshold());
        BitSet opp = ReliabilityMath.modifiedZOutliers(series.opp(), properties.outlierThreshold());
        if (mine != null || opp != null) {
            BitSet outliers = new BitSet(n);
            if (mine != null) {
                outliers.or(mine);
            }
            if (opp != null) {
                outliers.or(opp);
            }
            outlierCount = outliers.cardinality();
            if (outlierCount > 0 && outlierCount < n && errorMargin != null) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    if (!outliers.get(i)) {
                        sum += d[i];
                    }
                }
                double trimmedMean = sum / (n - outlierCount);
                suspicious = Math.abs(mean - trimmedMean) > errorMargin;
            }
        }

        Set<ReliabilityReason> reasons = EnumSet.noneOf(ReliabilityReason.class);
        if (insufficient) {
            reasons.add(ReliabilityReason.RELIABILITY_BELOW_TARGET);
        }
        if (suspicious) {
            reasons.add(ReliabilityReason.OUTLIER_IMPACT);
        }
        Double ciLow = errorMargin == null ? null : mean - errorMargin;
        Double ciHigh = errorMargin == null ? null : mean + errorMargin;
        if (caution == MetricCaution.UNRELIABLE) {
            return new MetricReliability(caution, false, false, Set.of(), sizes, errorMargin, ciLow, ciHigh,
                    null, null, null);
        }
        return new MetricReliability(caution, insufficient, suspicious, reasons, sizes, errorMargin, ciLow, ciHigh,
                threshold, reliability, outlierCount);
    }

    private static Double deltaValue(String key, DeltaStats d) {
        if (d == null) {
            return null;
        }
        return switch (key) {
            case "delta_opener" -> d.deltaOpener();
            case "delta_plonk" -> d.deltaPlonk();
            case "delta_stride" -> d.deltaStride();
            case "delta_inf_ds" -> d.deltaInfDs();
            case "delta_app" -> d.deltaApp();
            case "delta_weighted_app" -> d.deltaWeightedApp();
            case "delta_vs_apm" -> d.deltaVsApm();
            case "delta_cheese_index" -> d.deltaCheeseIndex();
            default -> throw new IllegalArgumentException("알 수 없는 지표: " + key);
        };
    }

    // ── strength_split: 비율 차이형 (1.4절, 2.5절, 5장) ──────────────

    private MetricReliability strengthSplit(List<MatchHistory> matches, boolean capped) {
        String key = "strength_split";
        MetricCaution caution = caution(key);
        StrengthSlices slices = MetricSamples.strengthSlices(matches);
        int n1 = slices.q1().size();
        int n5 = slices.q5().size();
        if (n1 == 0 || n5 == 0) {
            return null;
        }
        int w1 = (int) slices.q1().stream().filter(MatchHistory::isWin).count();
        int w5 = (int) slices.q5().stream().filter(MatchHistory::isWin).count();
        double value = (double) w5 / n5 - (double) w1 / n1;
        double[] ci = ReliabilityMath.newcombe(w5, n5, w1, n1, ReliabilityMath.Z95);
        Double margin = Math.max(value - ci[0], ci[1] - value);
        Map<SamplePart, Integer> sizes = new EnumMap<>(SamplePart.class);
        sizes.put(SamplePart.Q1, n1);
        sizes.put(SamplePart.Q5, n5);
        if (caution == MetricCaution.UNRELIABLE) {
            return new MetricReliability(caution, false, false, Set.of(), sizes, margin, ci[0], ci[1],
                    null, null, null);
        }

        Set<ReliabilityReason> reasons = EnumSet.noneOf(ReliabilityReason.class);
        if (n1 < properties.minQuintileMatches() || n5 < properties.minQuintileMatches()) {
            reasons.add(ReliabilityReason.BELOW_MIN_SAMPLE);                     // 1단계 — 2단계 생략
        } else if (!capped && (n1 < properties.referenceQuintileMatches()
                || n5 < properties.referenceQuintileMatches())) {
            reasons.add(ReliabilityReason.BELOW_REFERENCE_SAMPLE);               // 2단계 — 상한의 1/3
        }
        List<MatchHistory> used = new ArrayList<>(slices.q1());
        used.addAll(slices.q5());
        if (opponentConcentrated(used)) {
            reasons.add(ReliabilityReason.OPPONENT_CONCENTRATION);               // 4단계 — 1단계 미달이어도 본다
        }
        return labels(caution, reasons, sizes, margin, ci[0], ci[1]);
    }

    /** 같은 상대가 3판 이상이면서 분모(지표 값에 들어간 매치)의 10% 이상 */
    private boolean opponentConcentrated(List<MatchHistory> used) {
        Map<PseudonymId, Integer> counts = new HashMap<>();
        int max = 0;
        for (MatchHistory m : used) {
            if (m.opponentId() == null) {
                continue;
            }
            max = Math.max(max, counts.merge(m.opponentId(), 1, Integer::sum));
        }
        return max >= properties.opponentMinMatches() && max >= properties.opponentMinShare() * used.size();
    }

    // ── session_vs_slope: 기울기형 (1.4절, 2.6절, 5장) ───────────────

    private MetricReliability sessionVsSlope(double value, List<MatchHistory> matches, boolean capped,
                                             String seedKey) {
        String key = "session_vs_slope";
        MetricCaution caution = caution(key);
        List<RoundSeries> rounds = MetricSamples.roundSeries(matches);
        int indices = MetricSamples.distinctRoundIndices(rounds);
        int n = matches.size();
        Map<SamplePart, Integer> sizes = new EnumMap<>(SamplePart.class);
        sizes.put(SamplePart.ALL, n);
        sizes.put(SamplePart.ROUND_INDICES, indices);

        double[] ci = bootstrap(rounds, seed(seedKey, key));
        Double margin = ci == null ? null : Math.max(value - ci[0], ci[1] - value);
        Double low = ci == null ? null : ci[0];
        Double high = ci == null ? null : ci[1];
        if (caution == MetricCaution.UNRELIABLE) {
            return new MetricReliability(caution, false, false, Set.of(), sizes, margin, low, high, null, null, null);
        }

        Set<ReliabilityReason> reasons = EnumSet.noneOf(ReliabilityReason.class);
        if (indices < properties.minRoundIndices()) {
            reasons.add(ReliabilityReason.BELOW_MIN_SAMPLE);                     // 1단계 — 2단계 생략
        } else if (!capped && n < properties.referenceMatches()) {
            reasons.add(ReliabilityReason.BELOW_REFERENCE_SAMPLE);               // 2단계 — 상한의 1/2
        }
        return labels(caution, reasons, sizes, margin, low, high);
    }

    /**
     * 매치 단위 부트스트랩 95% 백분위 구간 (2.6절). 매치를 복원추출해 기울기를 B번 다시 구한다.
     * 정의 불가 표본은 버리고 다시 뽑는다. 계속 정의 불가면(시도 20B회 초과) null.
     */
    double[] bootstrap(List<RoundSeries> rounds, long seed) {
        int n = rounds.size();
        int b = properties.bootstrapIterations();
        if (n == 0) {
            return null;
        }
        SplittableRandom random = new SplittableRandom(seed);
        double[] slopes = new double[b];
        List<RoundSeries> sample = new ArrayList<>(n);
        int filled = 0;
        long attempts = 0;
        while (filled < b) {
            if (++attempts > 20L * b) {
                return null;
            }
            sample.clear();
            for (int i = 0; i < n; i++) {
                sample.add(rounds.get(random.nextInt(n)));
            }
            Double slope = MetricSamples.slopeOf(sample);
            if (slope != null) {
                slopes[filled++] = slope;
            }
        }
        Arrays.sort(slopes);
        int tail = (int) Math.round(b * ALPHA / 2);          // B = 2,000이면 양 끝 50번째 값
        return new double[] {slopes[Math.max(0, tail - 1)], slopes[Math.min(b - 1, b - tail)]};
    }

    /** 시드 = SHA-256(유저 이름 + ":" + 지표 키)의 앞 8바이트 */
    static long seed(String seedKey, String metric) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((seedKey + ":" + metric).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(h, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    // ── delta_comeback: 신뢰 불가 (1.2·1.4절) ────────────────────────

    /** 유저 단위 라벨은 판정하지 않고, 툴팁용 Newcombe 구간과 두 분모만 돌려준다 */
    private MetricReliability deltaComeback(HighlightStats h) {
        String key = "delta_comeback";
        MetricCaution caution = caution(key);
        int opp = h.comebackOpportunities();
        int against = h.comebackAgainstOpportunities();
        if (opp <= 0 || against <= 0) {
            return null;
        }
        double[] ci = ReliabilityMath.newcombe(h.comebackWon(), opp, h.comebackAgainstAllowed(), against,
                ReliabilityMath.Z95);
        double value = h.deltaComeback();
        double margin = Math.max(value - ci[0], ci[1] - value);
        Map<SamplePart, Integer> sizes = new EnumMap<>(SamplePart.class);
        sizes.put(SamplePart.COMEBACK, opp);
        sizes.put(SamplePart.COMEBACK_AGAINST, against);
        if (caution == MetricCaution.UNRELIABLE) {
            return new MetricReliability(caution, false, false, Set.of(), sizes, margin, ci[0], ci[1],
                    null, null, null);
        }
        // 시즌 갱신으로 R₃₀₀이 0.5 이상이 되면 다른 비율 지표처럼 라벨 없이 구간만 보인다 (판정 기준은 그때 정한다)
        return labels(caution, EnumSet.noneOf(ReliabilityReason.class), sizes, margin, ci[0], ci[1]);
    }

    // ── 라벨 결정 (1.5절) ────────────────────────────────────────────

    private static MetricReliability labels(MetricCaution caution, Set<ReliabilityReason> reasons,
                                            Map<SamplePart, Integer> sizes, Double margin, Double low, Double high) {
        boolean insufficient = reasons.stream().anyMatch(ReliabilityReason::sampleInsufficiency);
        boolean suspicious = reasons.stream().anyMatch(r -> !r.sampleInsufficiency());
        return new MetricReliability(caution, insufficient, suspicious, reasons, sizes, margin, low, high,
                null, null, null);
    }
}