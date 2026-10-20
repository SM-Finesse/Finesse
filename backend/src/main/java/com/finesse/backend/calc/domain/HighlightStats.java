package com.finesse.backend.calc.domain;

import java.util.List;

/**
 * 하이라이트 지표 (설계서 10.2절).
 * comeback 계열은 비율과 함께 모수(기회 수·성공 수)를 정수로 따로 반환한다.
 * deltaComeback = comebackRate − comebackRateAgainst이며, 둘 중 하나라도 null이면 null이다(LLM 하이라이트 후보).
 * eligible은 4개 Double 지표 중 하나라도 값이 있으면 true다.
 * strengthQuintiles는 strength_split의 분위별 승률(Q1 → Q5)이며, strengthSplit이 null이면 빈 목록이다 (11.2절, v3.8).
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
        boolean eligible,
        List<StrengthQuintile> strengthQuintiles
) {
    public HighlightStats {
        strengthQuintiles = strengthQuintiles == null ? List.of() : List.copyOf(strengthQuintiles);
    }

    /**
     * strength_split 분위 하나 — 헤비뷰 “상대 강도별 승률” 차트의 막대 하나 (11.2절, v3.8).
     *
     * @param quintile 1~5 (1 = 가장 약한 상대 구간, 5 = 가장 강한 상대 구간)
     * @param matches  그 분위의 판수
     * @param wins     그 분위의 승수
     * @param winRate  wins ÷ matches, 0~1 비율
     */
    public record StrengthQuintile(int quintile, int matches, int wins, double winRate) {}
}