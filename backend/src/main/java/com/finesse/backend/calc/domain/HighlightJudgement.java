package com.finesse.backend.calc.domain;

import java.util.List;

/**
 * 라이트 뷰 하이라이트 판정·선정 결과 (설계서 30.5절).
 *
 * @param rangesVersion 판정에 쓴 보통 범위 버전 (예: ref-2026-10-15-g2)
 * @param rangeGroup    판정에 쓴 범위 묶음 — 티어 묶음 이름(t1~t5) 또는 "default"(Z·등급 없음, v4.3)
 * @param metrics       1단계 판정 11개, 고정 순서(30.2절)
 * @param picks         2단계 선정 0~3개, pick1 → pick3
 */
public record HighlightJudgement(
        String rangesVersion,
        String rangeGroup,
        List<JudgedMetric> metrics,
        List<JudgedMetric> picks
) {
    public HighlightJudgement {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        picks = picks == null ? List.of() : List.copyOf(picks);
    }

    /** 선정 결과만 바꾼 사본 */
    public HighlightJudgement withPicks(List<JudgedMetric> newPicks) {
        return new HighlightJudgement(rangesVersion, rangeGroup, metrics, newPicks);
    }
}