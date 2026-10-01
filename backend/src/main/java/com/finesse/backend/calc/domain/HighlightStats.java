package com.finesse.backend.calc.domain;

/**
 * 하이라이트 지표 (설계서 10.2절).
 * comeback 계열은 비율과 함께 모수(기회 수·성공 수)를 정수로 따로 반환한다.
 * deltaComeback = comebackRate − comebackRateAgainst이며, 둘 중 하나라도 null이면 null이다(LLM 하이라이트 후보).
 * eligible은 4개 Double 지표 중 하나라도 값이 있으면 true다.
 */
public record HighlightStats(
        Double trTrendDelta,
        Double strengthSplit,
        int comebackOpportunities,
        int comebackWon,
        Double comebackRate,
        int comebackAgainstOpportunities,
        int comebackAgainstAllowed,
        Double comebackRateAgainst,
        Double deltaComeback,
        double sessionVsSlope,
        boolean eligible
) {}