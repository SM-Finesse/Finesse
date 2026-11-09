package com.finesse.backend.calc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 지표 신뢰도 판정 설정 (「지표 신뢰도 판정 기준」).
 * 값을 하나도 적지 않으면 코드 기본값으로 동작한다 — application.yml에 넣지 않아도 된다.
 *
 * <ul>
 *   <li>metrics — 지표별 상수 σ_true·VIF·R₃₀₀. 시즌이 바뀔 때 다시 추정해 갱신한다(4장 #13). 적은 지표만 덮어쓴다</li>
 *   <li>targetReliability — 목표 신뢰도 R (0.9). Δ 8종 표본 부족 기준이자 지표 단위 "해석 주의" 경계(R₃₀₀ < R)</li>
 *   <li>unreliableBelow — 지표 단위 "신뢰 불가" 경계 (R₃₀₀ < 0.5)</li>
 *   <li>나머지 — 1·2·4단계 기준값과 부트스트랩 반복 수, 이상치 임계값 (1.4절, 2.6·2.7절)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "finesse.analytics.reliability")
public record ReliabilityProperties(
        String version,
        Double targetReliability,
        Double unreliableBelow,
        Integer minQuintileMatches,
        Integer referenceQuintileMatches,
        Integer minRoundIndices,
        Integer referenceMatches,
        Integer opponentMinMatches,
        Double opponentMinShare,
        Integer bootstrapIterations,
        Double outlierThreshold,
        Map<String, MetricConstants> metrics
) {
    public static final String DEFAULT_VERSION = "rel-2026-10-26-kr5250";

    /**
     * 지표 하나의 상수.
     *
     * @param sigmaTrue 유저 간 실제 표준편차 σ_true (0 이상)
     * @param vif       매치 간 상관 분산 배율 (1 이상). Δ 8종만 쓰고 나머지는 1
     * @param r300      300판 기준 신뢰도 R₃₀₀ (0~1). 지표 단위 표시를 정한다
     */
    public record MetricConstants(double sigmaTrue, double vif, double r300) {
        public MetricConstants {
            if (sigmaTrue < 0) {
                throw new IllegalArgumentException("sigma-true는 0 이상이어야 합니다: " + sigmaTrue);
            }
            if (vif < 1) {
                throw new IllegalArgumentException("vif는 1 이상이어야 합니다: " + vif);
            }
            if (r300 < 0 || r300 > 1) {
                throw new IllegalArgumentException("r300은 0~1이어야 합니다: " + r300);
            }
        }
    }

    /** KR 리더보드 전원 5,250명 실측 (2026-10-26, 3.3·3.9절, 2.10절) */
    public static final Map<String, MetricConstants> DEFAULT_METRICS = defaults(
            "delta_opener", 0.302, 1.81, 0.985,
            "delta_plonk", 0.169, 1.48, 0.985,
            "delta_stride", 0.300, 1.51, 0.987,
            "delta_inf_ds", 0.337, 1.78, 0.987,
            "delta_app", 0.0661, 1.45, 0.986,
            "delta_weighted_app", 0.0377, 1.20, 0.980,
            "delta_vs_apm", 0.171, 1.72, 0.986,
            "delta_cheese_index", 18.67, 1.80, 0.987,
            "strength_split", 0.129, 1.0, 0.664,
            "delta_comeback", 0.0, 1.0, 0.0,
            "session_vs_slope", 0.836, 1.0, 0.742);

    @ConstructorBinding
    public ReliabilityProperties {
        version = version == null ? DEFAULT_VERSION : version.trim();
        if (version.isEmpty()) {
            throw new IllegalArgumentException("finesse.analytics.reliability.version이 비어 있습니다");
        }
        targetReliability = between(targetReliability, 0.9, "target-reliability");
        unreliableBelow = between(unreliableBelow, 0.5, "unreliable-below");
        if (unreliableBelow > targetReliability) {
            throw new IllegalArgumentException("unreliable-below는 target-reliability 이하여야 합니다");
        }
        minQuintileMatches = positive(minQuintileMatches, 5, "min-quintile-matches");
        referenceQuintileMatches = positive(referenceQuintileMatches, 20, "reference-quintile-matches");
        minRoundIndices = positive(minRoundIndices, 3, "min-round-indices");
        referenceMatches = positive(referenceMatches, 150, "reference-matches");
        opponentMinMatches = positive(opponentMinMatches, 3, "opponent-min-matches");
        opponentMinShare = between(opponentMinShare, 0.10, "opponent-min-share");
        bootstrapIterations = positive(bootstrapIterations, 2000, "bootstrap-iterations");
        if (bootstrapIterations < 40) {
            throw new IllegalArgumentException("bootstrap-iterations는 40 이상이어야 합니다: " + bootstrapIterations);
        }
        outlierThreshold = outlierThreshold == null ? 3.5 : outlierThreshold;
        if (outlierThreshold <= 0) {
            throw new IllegalArgumentException("outlier-threshold는 0보다 커야 합니다: " + outlierThreshold);
        }
        metrics = merge(metrics);
    }

    /** 모두 코드 기본값인 설정 — 테스트와 Spring 밖 생성자에서 쓴다 */
    public static ReliabilityProperties defaults() {
        return new ReliabilityProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public MetricConstants constants(String key) {
        return metrics.get(key);
    }

    private static Map<String, MetricConstants> merge(Map<String, MetricConstants> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return DEFAULT_METRICS;
        }
        Map<String, MetricConstants> merged = new LinkedHashMap<>(DEFAULT_METRICS);
        overrides.forEach((key, value) -> {
            String metric = canonical(key);
            if (value == null) {
                throw new IllegalArgumentException("reliability.metrics." + key + " 값이 비어 있습니다");
            }
            merged.put(metric, value);
        });
        return Collections.unmodifiableMap(merged);
    }

    /** yml 키 delta_app · delta-app · deltaapp를 모두 delta_app으로 맞춘다 */
    private static String canonical(String key) {
        String squashed = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (String metric : DEFAULT_METRICS.keySet()) {
            if (metric.replace("_", "").equals(squashed)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("알 수 없는 지표: " + key);
    }

    private static Map<String, MetricConstants> defaults(Object... rows) {
        Map<String, MetricConstants> map = new LinkedHashMap<>();
        for (int i = 0; i < rows.length; i += 4) {
            map.put((String) rows[i], new MetricConstants((double) rows[i + 1], (double) rows[i + 2],
                    (double) rows[i + 3]));
        }
        return Collections.unmodifiableMap(map);
    }

    private static double between(Double value, double fallback, String name) {
        double v = value == null ? fallback : value;
        if (v <= 0 || v >= 1) {
            throw new IllegalArgumentException(name + "는 0 < 값 < 1이어야 합니다: " + v);
        }
        return v;
    }

    private static int positive(Integer value, int fallback, String name) {
        int v = value == null ? fallback : value;
        if (v <= 0) {
            throw new IllegalArgumentException(name + "는 1 이상이어야 합니다: " + v);
        }
        return v;
    }
}