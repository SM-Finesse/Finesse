package com.finesse.backend.calc.domain;

import java.util.Locale;

/**
 * 지표 단위 신뢰도 표시 (「지표 신뢰도 판정 기준」 1.2절).
 * 유저와 관계없이 지표마다 고정이며, 300판 기준 신뢰도 R₃₀₀으로 정한다. 시즌이 바뀔 때 다시 정한다.
 * <ul>
 *   <li>NONE — 표시 없음 (R₃₀₀ ≥ 0.9)</li>
 *   <li>INTERPRETATION_CAUTION — "해석 주의" (R₃₀₀ < 0.9): 300판을 모아도 값 차이의 일부가 표본 운이다</li>
 *   <li>UNRELIABLE — "신뢰 불가" (R₃₀₀ < 0.5): 유저 간 차이를 구분할 수 없다. 유저 단위 라벨은 판정하지 않는다</li>
 * </ul>
 */
public enum MetricCaution {
    NONE, INTERPRETATION_CAUTION, UNRELIABLE;

    /** 응답에 쓰는 소문자 이름 ("none" · "interpretation_caution" · "unreliable") */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}