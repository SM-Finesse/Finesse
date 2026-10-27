package com.finesse.backend.calc.reliability;

import com.finesse.backend.calc.calculator.MetricSamples;
import com.finesse.backend.calc.domain.MatchHistory;

import java.util.List;

/** 테스트용 — 지표 값과 같은 기울기 (MetricSamplesTest에서 sessionVsSlope와 같음을 확인한다) */
final class HighlightStatCalculatorAccess {

    private HighlightStatCalculatorAccess() {}

    static Double slope(List<MatchHistory> matches) {
        return MetricSamples.slopeOf(MetricSamples.roundSeries(matches));
    }
}