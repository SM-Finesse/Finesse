package com.finesse.backend.calc.domain;

import java.util.Locale;

/**
 * 유저 단위 신뢰도 라벨의 근거 (「지표 신뢰도 판정 기준」 1.3·1.6절).
 * <ul>
 *   <li>BELOW_MIN_SAMPLE — 1단계 최소 표본 미달 (strength_split Q1·Q5 각 5판, session_vs_slope 라운드 순번 3개) → 표본 부족</li>
 *   <li>RELIABILITY_BELOW_TARGET — Δ 8종 신뢰도 R_i가 목표(0.9) 미만 → 표본 부족</li>
 *   <li>BELOW_REFERENCE_SAMPLE — strength_split·session_vs_slope 표본이 수집 상한 대비 기준 미만 → 표본 부족</li>
 *   <li>OUTLIER_IMPACT — Δ 8종 이상치가 평균을 관측 오차보다 크게 움직임 → 의심 데이터</li>
 *   <li>OPPONENT_CONCENTRATION — strength_split Q1·Q5 매치가 같은 상대에 몰림 → 의심 데이터</li>
 * </ul>
 */
public enum ReliabilityReason {
    BELOW_MIN_SAMPLE,
    RELIABILITY_BELOW_TARGET,
    BELOW_REFERENCE_SAMPLE,
    OUTLIER_IMPACT,
    OPPONENT_CONCENTRATION;

    /** 표본 부족 라벨의 근거인지 (아니면 의심 데이터 라벨의 근거) */
    public boolean sampleInsufficiency() {
        return this == BELOW_MIN_SAMPLE || this == RELIABILITY_BELOW_TARGET || this == BELOW_REFERENCE_SAMPLE;
    }

    /** 응답에 쓰는 소문자 이름 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}