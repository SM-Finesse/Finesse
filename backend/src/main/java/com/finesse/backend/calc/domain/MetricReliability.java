package com.finesse.backend.calc.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 하이라이트 후보 지표 하나의 신뢰도 (「지표 신뢰도 판정 기준」 1.6절).
 * 화면 표시 전용이며 LLM 요청과 하이라이트 선정에는 쓰지 않는다. 지표 값을 바꾸지 않는다.
 * 지표 값이 null이면 이 객체도 만들지 않는다(ReliabilityReport에 없음).
 *
 * @param caution            지표 단위 표시 (지표마다 고정)
 * @param sampleInsufficient "! 표본 부족" — caution이 UNRELIABLE이면 항상 false
 * @param suspiciousData     "! 의심 데이터 포함" — caution이 UNRELIABLE이면 항상 false
 * @param reasons            붙은 라벨의 근거 전부. 라벨이 없으면 빈 집합
 * @param sampleSizes        표본별 크기
 * @param errorMargin        관측 오차 E_obs (95% 반폭). Δ 8종은 √VIF 포함, 비대칭 구간은 큰 쪽. 미적용이면 null
 * @param ciLow              95% 구간 하한, 미적용이면 null
 * @param ciHigh             95% 구간 상한, 미적용이면 null
 * @param threshold          비교한 기준 E_R,i (Δ 8종), 그 외 null
 * @param reliability        신뢰도 R_i (Δ 8종), 그 외 null
 * @param outlierCount       이상치 매치 수 (Δ 8종). 탐지하지 않았으면 null, 0은 "탐지했고 0개"
 */
public record MetricReliability(
        MetricCaution caution,
        boolean sampleInsufficient,
        boolean suspiciousData,
        Set<ReliabilityReason> reasons,
        Map<SamplePart, Integer> sampleSizes,
        Double errorMargin,
        Double ciLow,
        Double ciHigh,
        Double threshold,
        Double reliability,
        Integer outlierCount
) {
    public MetricReliability {
        reasons = reasons == null || reasons.isEmpty()
                ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(reasons));
        sampleSizes = sampleSizes == null || sampleSizes.isEmpty()
                ? Collections.emptyMap() : Collections.unmodifiableMap(new EnumMap<>(sampleSizes));
    }
}