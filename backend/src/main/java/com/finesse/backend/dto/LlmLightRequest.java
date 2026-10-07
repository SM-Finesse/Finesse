package com.finesse.backend.dto;

import java.util.List;
import java.util.Set;

/**
 * 백엔드 → LLM 서버, light 스코프 요청 — LLM/AI 파트 설계 v1.2 5.2절 스키마.
 * 추론 서버는 정의되지 않은 필드가 오면 요청을 거절하므로(extra=forbid) stats 응답을 그대로 넘기지 않고
 * 하이라이트 후보 11개만 골라 담는다 (하이라이트 지표 설계 9/30). tr_trend_delta·comeback_rate·
 * comeback_rate_against·recent_form은 light 후보가 아니라서 넣지 않는다.
 */
public record LlmLightRequest(FixedMetrics fixedMetrics, DeltaMetrics deltaMetrics) {

    /** 하이라이트 후보 11개 평탄 키 — 응답 highlights[].stat은 이 중 하나여야 한다(v1.2 5.1절). */
    public static final Set<String> HIGHLIGHT_STATS = Set.of(
            "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
            "delta_app", "delta_weighted_app",
            "delta_vs_apm", "delta_cheese_index",
            "strength_split", "delta_comeback", "session_vs_slope");

    public record FixedMetrics(Double winRate, List<Double> trTrend) {
    }

    public record DeltaMetrics(
            StatsResponse.PlaystyleRelative playstyleRelative,
            StatsResponse.Attack attack,
            StatsResponse.Defense defense,
            Double strengthSplit,
            Double deltaComeback,
            Double sessionVsSlope
    ) {
    }

    /**
     * 이번 요청에 값이 실제로 들어간 후보 키 — LLM이 고를 수 있는 범위(v1.2 5.2절 available_stats와 같은 기준).
     * 값이 없는(생략된) 지표는 근거가 될 수 없으므로 응답 검증도 이 집합으로 한다.
     */
    public Set<String> availableStats() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        DeltaMetrics d = deltaMetrics;
        if (d == null) {
            return keys;
        }
        StatsResponse.PlaystyleRelative p = d.playstyleRelative();
        if (p != null) {
            addIf(keys, "delta_opener", p.deltaOpener());
            addIf(keys, "delta_plonk", p.deltaPlonk());
            addIf(keys, "delta_stride", p.deltaStride());
            addIf(keys, "delta_inf_ds", p.deltaInfDs());
        }
        if (d.attack() != null) {
            keys.add("delta_app");
            keys.add("delta_weighted_app");
        }
        if (d.defense() != null) {
            keys.add("delta_vs_apm");
            keys.add("delta_cheese_index");
        }
        addIf(keys, "strength_split", d.strengthSplit());
        addIf(keys, "delta_comeback", d.deltaComeback());
        addIf(keys, "session_vs_slope", d.sessionVsSlope());
        return keys;
    }

    private static void addIf(Set<String> keys, String key, Double value) {
        if (value != null) {
            keys.add(key);
        }
    }

    /** light는 콜드스타트가 아닐 때만 LLM을 부르므로 delta_metrics가 있는 stats만 들어온다. */
    public static LlmLightRequest from(StatsResponse stats) {
        StatsResponse.FixedMetrics f = stats.fixedMetrics();
        StatsResponse.DeltaMetrics d = stats.deltaMetrics();
        return new LlmLightRequest(
                new FixedMetrics(f.winRate(), f.trTrend()),
                new DeltaMetrics(d.playstyleRelative(), d.attack(), d.defense(), d.strengthSplit(),
                        d.deltaComeback(), d.sessionVsSlope()));
    }
}
