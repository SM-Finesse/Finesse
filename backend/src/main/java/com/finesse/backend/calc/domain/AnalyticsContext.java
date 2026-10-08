package com.finesse.backend.calc.domain;

import java.util.List;

/**
 * Calculator 입력 — 정제·가명처리를 마친 분석 매치(최대 300판) (설계서 19.3절).
 * v3.10: 이전 구간(previousWindowMatches)을 없앴다. 구간 비교는 이 목록 안에서 최근 N판 vs 나머지로 한다(11.11절).
 */
public record AnalyticsContext(
        List<MatchHistory> matches
) {
    public AnalyticsContext {
        matches = List.copyOf(matches);
    }
}