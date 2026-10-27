package com.finesse.backend.calc.highlight;

import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.config.HighlightProperties.Range;
import com.finesse.backend.calc.config.HighlightProperties.ResolvedRanges;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 라이트 뷰 하이라이트 1단계 — 판정 (설계서 30.3절).
 * 후보 11개를 보통 범위와 비교해 영역(HIGH·NORMAL·LOW·NONE)과 벗어난 정도를 낸다.
 * 범위는 조회 시점 등급으로 고른 티어 묶음 범위(없으면 기본 범위)다 (v4.3).
 * 계산은 모두 BigDecimal로 한다 — 반올림 경계에서 double 오차로 결과가 바뀌지 않게 하기 위해서다.
 */
@Component
public class HighlightJudge {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal MIN_SHOWN = new BigDecimal("0.01");

    private final HighlightProperties properties;

    public HighlightJudge(HighlightProperties properties) {
        this.properties = properties;
    }

    /**
     * @param delta     Δ 지표 (null이면 1~8번 후보가 NONE)
     * @param highlight 하이라이트 지표 (null이면 9~11번 후보가 NONE)
     * @param rank      조회 시점 등급(UserSummary.rank). null·z·목록 밖이면 기본 범위
     * @return 판정 11개. picks는 비어 있다(2단계에서 채운다)
     */
    public HighlightJudgement judge(DeltaStats delta, HighlightStats highlight, String rank) {
        ResolvedRanges resolved = properties.rangesFor(rank);
        Map<String, Range> ranges = resolved.ranges();
        List<JudgedMetric> out = new ArrayList<>(HighlightProperties.METRICS.size());
        for (String key : HighlightProperties.METRICS) {
            Range range = ranges.get(key);
            out.add(switch (key) {
                case "delta_comeback" -> judgeComeback(highlight, range);
                default -> judgeValue(key, rawValue(key, delta, highlight), range);
            });
        }
        return new HighlightJudgement(properties.rangesVersion(), resolved.name(), out, List.of());
    }

    // ── 지표별 값 ────────────────────────────────────────────────────

    private static Double rawValue(String key, DeltaStats d, HighlightStats h) {
        return switch (key) {
            case "delta_opener" -> d == null ? null : d.deltaOpener();
            case "delta_plonk" -> d == null ? null : d.deltaPlonk();
            case "delta_stride" -> d == null ? null : d.deltaStride();
            case "delta_inf_ds" -> d == null ? null : d.deltaInfDs();
            case "delta_app" -> d == null ? null : d.deltaApp();
            case "delta_weighted_app" -> d == null ? null : d.deltaWeightedApp();
            case "delta_vs_apm" -> d == null ? null : d.deltaVsApm();
            case "delta_cheese_index" -> d == null ? null : d.deltaCheeseIndex();
            case "strength_split" -> h == null ? null : h.strengthSplit();
            // v4.4: 정의 불가는 null이라 값 없음 규칙으로 NONE. 실제 0.0은 다른 지표처럼 범위와 비교한다
            case "session_vs_slope" -> h == null ? null : h.sessionVsSlope();
            default -> throw new IllegalArgumentException("알 수 없는 지표: " + key);
        };
    }


    /**
     * delta_comeback — 축소값으로 판정한다 (v4.3).
     * 축소값 = (성공 + k·p0)/(기회 + k) − (역전당함 + k·q0)/(허용 기회 + k).
     * 두 기회 중 하나라도 0이면 원값도 없으므로 NONE이다.
     */
    private JudgedMetric judgeComeback(HighlightStats h, Range range) {
        String key = "delta_comeback";
        if (h == null) {
            return none(key, null, range);
        }
        if (h.comebackOpportunities() <= 0 || h.comebackAgainstOpportunities() <= 0) {
            return none(key, h.deltaComeback(), range);
        }
        BigDecimal k = BigDecimal.valueOf(properties.comebackShrinkK());
        BigDecimal rate = shrink(h.comebackWon(), h.comebackOpportunities(), k, properties.comebackPriorRate());
        BigDecimal against = shrink(h.comebackAgainstAllowed(), h.comebackAgainstOpportunities(), k,
                properties.comebackAgainstPriorRate());
        return judge(key, rate.subtract(against, MC), range);
    }

    private static BigDecimal shrink(int hits, int chances, BigDecimal k, BigDecimal prior) {
        BigDecimal numerator = BigDecimal.valueOf(hits).add(k.multiply(prior, MC), MC);
        BigDecimal denominator = BigDecimal.valueOf(chances).add(k, MC);
        return numerator.divide(denominator, MC);
    }

    // ── 판정 ─────────────────────────────────────────────────────────

    private static JudgedMetric judgeValue(String key, Double raw, Range range) {
        if (raw == null || raw.isNaN() || raw.isInfinite()) {
            return none(key, null, range);
        }
        return judge(key, BigDecimal.valueOf(raw), range);
    }

    /** 30.3절 — 경계값은 NORMAL, 영역은 반올림 전 값으로 정한다 */
    private static JudgedMetric judge(String key, BigDecimal v, Range range) {
        BigDecimal q1 = range.q1();
        BigDecimal q3 = range.q3();
        BigDecimal w = range.width();
        if (v.compareTo(q3) > 0) {
            BigDecimal d = v.subtract(q3, MC).divide(w, MC);
            return metric(key, v, range, HighlightZone.HIGH, shown(d), null);
        }
        if (v.compareTo(q1) < 0) {
            BigDecimal d = q1.subtract(v, MC).divide(w, MC).negate();
            return metric(key, v, range, HighlightZone.LOW, shown(d), null);
        }
        BigDecimal gap = v.subtract(q1, MC).min(q3.subtract(v, MC)).divide(w, MC);
        return metric(key, v, range, HighlightZone.NORMAL, 0.0, gap.doubleValue());
    }

    /** 소수 셋째 자리에서 HALF_UP → 둘째 자리. 범위 밖인데 0.00이면 ±0.01 */
    static double shown(BigDecimal d) {
        BigDecimal s = d.setScale(2, RoundingMode.HALF_UP);
        if (s.signum() == 0) {
            s = d.signum() > 0 ? MIN_SHOWN : MIN_SHOWN.negate();
        }
        return s.doubleValue();
    }

    private static JudgedMetric metric(String key, BigDecimal v, Range range, HighlightZone zone,
                                       Double deviation, Double edgeGap) {
        return new JudgedMetric(key, groupOf(key), v.doubleValue(), range.q1().doubleValue(),
                range.q3().doubleValue(), zone, deviation, edgeGap);
    }

    private static JudgedMetric none(String key, Double value, Range range) {
        Double v = value == null || value.isNaN() || value.isInfinite() ? null : value;
        return new JudgedMetric(key, groupOf(key), v, range.q1().doubleValue(), range.q3().doubleValue(),
                HighlightZone.NONE, null, null);
    }

    /** 선정 그룹 — 플레이스타일 4개·공격 2개·수비 2개, 나머지 3개는 각자 한 그룹 (30.2절) */
    static String groupOf(String key) {
        return switch (key) {
            case "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds" -> "playstyle";
            case "delta_app", "delta_weighted_app" -> "attack";
            case "delta_vs_apm", "delta_cheese_index" -> "defense";
            default -> key;
        };
    }
}