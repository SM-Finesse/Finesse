package com.finesse.backend.calc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 라이트 뷰 하이라이트 판정·선정 설정 (설계서 30.6절, v4.3).
 * 값을 하나도 적지 않으면 코드 기본값으로 동작한다 — application.yml은 백엔드 담당 파일이라 넣지 않아도 된다.
 *
 * <ul>
 *   <li>ranges — 기본 범위(Z·등급 없음·목록에 없는 등급). 적은 지표만 기본값을 덮어쓴다</li>
 *   <li>tierRanges — 티어 묶음별 범위. 적으면 기본 묶음 전체를 대신하며, 묶음 안에서 빠진 지표는 ranges 값을 쓴다</li>
 *   <li>comeback* — delta_comeback 축소 판정: (성공 + k·p0)/(기회 + k) − (역전당함 + k·q0)/(허용 기회 + k)</li>
 * </ul>
 * 지표 키는 yml에서 delta_app·delta-app·deltaapp 어느 형태로 적어도 된다(Spring이 Map 키의 '_'를 지우기 때문).
 */
@ConfigurationProperties(prefix = "finesse.analytics.highlight")
public record HighlightProperties(
        String rangesVersion,
        BigDecimal groupPenalty,
        Integer comebackShrinkK,
        BigDecimal comebackPriorRate,
        BigDecimal comebackAgainstPriorRate,
        Map<String, Range> ranges,
        Map<String, TierGroup> tierRanges
) {
    /** 후보 11개, 고정 순서 (30.2절) */
    public static final List<String> METRICS = List.of(
            "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
            "delta_app", "delta_weighted_app", "delta_vs_apm", "delta_cheese_index",
            "strength_split", "delta_comeback", "session_vs_slope");

    /** 랭크 티어(소문자). z는 비랭크라 묶음에 넣지 않는다 */
    public static final List<String> TIERS = List.of(
            "d", "d+", "c-", "c", "c+", "b-", "b", "b+", "a-", "a", "a+",
            "s-", "s", "s+", "ss", "u", "x", "x+");

    public static final String DEFAULT_GROUP = "default";
    public static final String DEFAULT_RANGES_VERSION = "ref-2026-10-15-g2";
    public static final BigDecimal DEFAULT_GROUP_PENALTY = new BigDecimal("0.5");
    public static final int DEFAULT_COMEBACK_SHRINK_K = 20;
    public static final BigDecimal DEFAULT_COMEBACK_PRIOR_RATE = new BigDecimal("0.100");
    public static final BigDecimal DEFAULT_COMEBACK_AGAINST_PRIOR_RATE = new BigDecimal("0.096");

    /** 기본 범위 — train 498명(train-2026-10-14). delta_comeback만 축소값 기준으로 다시 산출 (v4.3) */
    public static final Map<String, Range> DEFAULT_RANGES = ranges(
            "-0.249", "0.171", "-0.122", "0.119", "-0.162", "0.189", "-0.157", "0.255",
            "-0.043", "0.024", "-0.026", "0.026", "-0.100", "0.138", "-7.949", "13.852",
            "-0.395", "-0.139", "-0.026", "0.031", "-0.080", "1.370");

    /** 티어 묶음 범위 — ref 1,614명, 인원이 비슷한 5묶음(G2). 「티어별 보통범위 분석보고서」 (v4.3) */
    public static final Map<String, TierGroup> DEFAULT_TIER_RANGES = tierGroups(
            new TierGroup(List.of("d", "d+", "c-", "c"), ranges(
                    "-0.362", "0.025", "-0.094", "0.107", "-0.088", "0.266", "-0.041", "0.296",
                    "-0.068", "-0.010", "-0.037", "0.014", "-0.037", "0.173", "0.505", "17.924",
                    "-0.500", "-0.099", "-0.052", "0.002", "-0.374", "0.764")),
            new TierGroup(List.of("c+", "b-", "b", "b+"), ranges(
                    "-0.259", "0.155", "-0.117", "0.133", "-0.163", "0.173", "-0.152", "0.273",
                    "-0.035", "0.015", "-0.023", "0.026", "-0.093", "0.160", "-7.575", "13.424",
                    "-0.312", "-0.074", "-0.025", "0.035", "-0.128", "1.019")),
            new TierGroup(List.of("a-", "a", "a+"), ranges(
                    "-0.207", "0.265", "-0.138", "0.114", "-0.203", "0.176", "-0.273", "0.236",
                    "-0.034", "0.042", "-0.025", "0.028", "-0.156", "0.121", "-15.231", "12.588",
                    "-0.308", "-0.122", "-0.021", "0.037", "0.023", "1.610")),
            new TierGroup(List.of("s-", "s", "s+"), ranges(
                    "-0.130", "0.217", "-0.124", "0.132", "-0.241", "0.170", "-0.244", "0.147",
                    "-0.041", "0.054", "-0.027", "0.032", "-0.125", "0.069", "-14.000", "9.165",
                    "-0.426", "-0.272", "-0.019", "0.038", "0.493", "1.671")),
            new TierGroup(List.of("ss", "u", "x", "x+"), ranges(
                    "-0.058", "0.130", "-0.163", "0.110", "-0.176", "0.194", "-0.139", "0.050",
                    "-0.037", "0.042", "-0.037", "0.034", "-0.066", "0.028", "-9.325", "3.221",
                    "-0.644", "-0.374", "-0.006", "0.039", "0.917", "2.018")));

    /** 보통 범위 하나. Q1 < Q3여야 한다 */
    public record Range(BigDecimal q1, BigDecimal q3) {
        public Range {
            if (q1 == null || q3 == null) {
                throw new IllegalArgumentException("보통 범위는 q1·q3를 모두 적어야 합니다");
            }
            if (q1.compareTo(q3) >= 0) {
                throw new IllegalArgumentException("보통 범위는 q1 < q3여야 합니다: " + q1 + " ~ " + q3);
            }
        }

        public BigDecimal width() {
            return q3.subtract(q1);
        }
    }

    /** 티어 묶음 — 소문자 티어 목록과 지표별 범위 */
    public record TierGroup(List<String> tiers, Map<String, Range> ranges) {
        public TierGroup {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
            ranges = ranges == null ? Map.of() : ranges;
        }
    }

    /** 판정에 쓸 범위 묶음 — name은 묶음 이름 또는 "default" */
    public record ResolvedRanges(String name, Map<String, Range> ranges) {}

    @ConstructorBinding
    public HighlightProperties {
        rangesVersion = rangesVersion == null ? DEFAULT_RANGES_VERSION : rangesVersion.trim();
        if (rangesVersion.isEmpty()) {
            throw new IllegalArgumentException("finesse.analytics.highlight.ranges-version이 비어 있습니다");
        }
        groupPenalty = groupPenalty == null ? DEFAULT_GROUP_PENALTY : groupPenalty;
        if (groupPenalty.signum() <= 0 || groupPenalty.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("group-penalty는 0 < 값 ≤ 1이어야 합니다: " + groupPenalty);
        }
        comebackShrinkK = comebackShrinkK == null ? DEFAULT_COMEBACK_SHRINK_K : comebackShrinkK;
        if (comebackShrinkK < 0) {
            throw new IllegalArgumentException("comeback-shrink-k는 0 이상이어야 합니다: " + comebackShrinkK);
        }
        comebackPriorRate = rate(comebackPriorRate, DEFAULT_COMEBACK_PRIOR_RATE, "comeback-prior-rate");
        comebackAgainstPriorRate = rate(comebackAgainstPriorRate, DEFAULT_COMEBACK_AGAINST_PRIOR_RATE,
                "comeback-against-prior-rate");
        ranges = merge(DEFAULT_RANGES, ranges, "ranges");
        tierRanges = tierRanges == null ? DEFAULT_TIER_RANGES : mergeTierGroups(ranges, tierRanges);
    }

    /** 모두 코드 기본값인 설정 — 테스트와 Spring 밖 생성자에서 쓴다 */
    public static HighlightProperties defaults() {
        return new HighlightProperties(null, null, null, null, null, null, null);
    }

    /**
     * 조회 시점 등급(UserSummary.rank)으로 범위 묶음을 고른다.
     * 대소문자는 무시하고, null·빈 값·z·목록에 없는 등급이면 기본 범위를 쓴다.
     */
    public ResolvedRanges rangesFor(String rank) {
        if (rank != null) {
            String tier = rank.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, TierGroup> group : tierRanges.entrySet()) {
                if (group.getValue().tiers().contains(tier)) {
                    return new ResolvedRanges(group.getKey(), group.getValue().ranges());
                }
            }
        }
        return new ResolvedRanges(DEFAULT_GROUP, ranges);
    }

    // ── 병합·검사 ────────────────────────────────────────────────────

    private static BigDecimal rate(BigDecimal value, BigDecimal fallback, String name) {
        BigDecimal v = value == null ? fallback : value;
        if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + "는 0 ~ 1이어야 합니다: " + v);
        }
        return v;
    }

    /** base 위에 override를 덮어쓴 11개 지표 범위(고정 순서). 모르는 지표 키는 오류 */
    private static Map<String, Range> merge(Map<String, Range> base, Map<String, Range> override, String where) {
        Map<String, Range> out = new LinkedHashMap<>(base);
        if (override != null) {
            for (Map.Entry<String, Range> e : override.entrySet()) {
                out.put(canonicalMetric(e.getKey(), where), e.getValue());
            }
        }
        return Collections.unmodifiableMap(ordered(out));
    }

    private static Map<String, TierGroup> mergeTierGroups(Map<String, Range> base, Map<String, TierGroup> groups) {
        Map<String, TierGroup> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, TierGroup> e : groups.entrySet()) {
            String name = e.getKey();
            TierGroup group = e.getValue();
            if (group.tiers().isEmpty()) {
                throw new IllegalArgumentException("tier-ranges." + name + ".tiers가 비어 있습니다");
            }
            List<String> tiers = new ArrayList<>();
            for (String t : group.tiers()) {
                String tier = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
                if (!TIERS.contains(tier)) {
                    throw new IllegalArgumentException("tier-ranges." + name + "에 알 수 없는 티어: " + t);
                }
                if (!seen.add(tier)) {
                    throw new IllegalArgumentException("티어가 두 묶음에 들어 있습니다: " + tier);
                }
                tiers.add(tier);
            }
            out.put(name, new TierGroup(tiers, merge(base, group.ranges(), "tier-ranges." + name)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String canonicalMetric(String key, String where) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String metric : METRICS) {
            if (metric.replace("_", "").equals(normalized)) {
                return metric;
            }
        }
        throw new IllegalArgumentException(where + "에 알 수 없는 지표: " + key);
    }

    private static Map<String, Range> ordered(Map<String, Range> map) {
        Map<String, Range> out = new LinkedHashMap<>();
        for (String metric : METRICS) {
            out.put(metric, map.get(metric));
        }
        return out;
    }

    private static Map<String, Range> ranges(String... q) {
        Map<String, Range> out = new LinkedHashMap<>();
        for (int i = 0; i < METRICS.size(); i++) {
            out.put(METRICS.get(i), new Range(new BigDecimal(q[2 * i]), new BigDecimal(q[2 * i + 1])));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, TierGroup> tierGroups(TierGroup... groups) {
        Map<String, TierGroup> out = new LinkedHashMap<>();
        for (int i = 0; i < groups.length; i++) {
            out.put("t" + (i + 1), groups[i]);
        }
        return Collections.unmodifiableMap(out);
    }
}