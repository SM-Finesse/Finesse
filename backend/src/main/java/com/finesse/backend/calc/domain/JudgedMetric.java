package com.finesse.backend.calc.domain;

/**
 * 하이라이트 후보 지표 하나의 판정 결과 (설계서 30.5절).
 *
 * @param key       후보 키 (예: delta_app)
 * @param group     선정 그룹 — playstyle·attack·defense, 단독 지표는 자기 키
 * @param value     판정에 쓴 값(반올림하지 않음). 값이 없거나 NaN·∞면 null.
 *                  delta_comeback은 축소값(30.3절, v4.3)이며 원값은 HighlightStats.deltaComeback에 있다
 * @param q1        판정에 쓴 보통 범위 Q1 (티어 묶음 범위 또는 기본 범위)
 * @param q3        판정에 쓴 보통 범위 Q3
 * @param zone      영역
 * @param deviation 벗어난 정도 표시값(소수 둘째 자리, 범위 밖 최소 ±0.01). NORMAL은 0.0, NONE은 null
 * @param edgeGap   NORMAL일 때만 — 가까운 경계까지 거리 ÷ W. 그 밖이면 null
 */
public record JudgedMetric(
        String key,
        String group,
        Double value,
        double q1,
        double q3,
        HighlightZone zone,
        Double deviation,
        Double edgeGap
) {}