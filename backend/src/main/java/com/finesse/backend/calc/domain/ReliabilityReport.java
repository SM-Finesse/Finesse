package com.finesse.backend.calc.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 하이라이트 후보 11개의 신뢰도 묶음 (「지표 신뢰도 판정 기준」).
 * metrics의 키는 하이라이트 판정과 같은 지표 키(delta_app 등)이고 순서도 같다.
 * 값이 null인 지표는 키가 없다 — metric(key)는 null을 돌려준다.
 *
 * @param version 지표 단위 상수(σ_true·VIF·R₃₀₀)를 추정한 기준 버전. 시즌이 바뀔 때 갱신한다
 * @param metrics 지표 키 → 신뢰도
 */
public record ReliabilityReport(
        String version,
        Map<String, MetricReliability> metrics
) {
    public ReliabilityReport {
        metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    /** 지표 하나의 신뢰도. 지표 값이 없어 판정하지 않았으면 null */
    public MetricReliability metric(String key) {
        return metrics.get(key);
    }
}