package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 신뢰도 판정용 표본이 지표 계산과 같은 매치 집합·같은 공식을 쓰는지 (「지표 신뢰도 판정 기준」) */
class MetricSamplesTest {

    private static final double TOL = 1e-12;

    @Test
    void Δ_매치별_값의_평균은_DeltaStats_값과_같다() {
        List<MatchHistory> matches = List.of(
                delta(60, 1.0, 120, 30, 1.0, 60),
                delta(90, 1.5, 150, 90, 1.0, 150),
                delta(150, 3.0, 330, 120, 2.5, 260),
                delta(0, 1.0, 50, 60, 1.0, 120));        // APM 0 — Δ 계산에서 빠진다
        DeltaStats d = new DeltaStatCalculator().calculate(new AnalyticsContext(matches));

        assertThat(mean(MetricSamples.deltaSeries("delta_app", matches).deltas())).isCloseTo(d.deltaApp(), within(TOL));
        assertThat(mean(MetricSamples.deltaSeries("delta_weighted_app", matches).deltas()))
                .isCloseTo(d.deltaWeightedApp(), within(TOL));
        assertThat(mean(MetricSamples.deltaSeries("delta_vs_apm", matches).deltas()))
                .isCloseTo(d.deltaVsApm(), within(TOL));
        assertThat(mean(MetricSamples.deltaSeries("delta_cheese_index", matches).deltas()))
                .isCloseTo(d.deltaCheeseIndex(), within(TOL));
        assertThat(mean(MetricSamples.deltaSeries("delta_opener", matches).deltas()))
                .isCloseTo(d.deltaOpener(), within(TOL));
        assertThat(mean(MetricSamples.deltaSeries("delta_inf_ds", matches).deltas()))
                .isCloseTo(d.deltaInfDs(), within(TOL));
        assertThat(MetricSamples.deltaSeries("delta_app", matches).size()).isEqualTo(3);
    }

    @Test
    void Q1_Q5는_strength_split_분위와_같다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            matches.add(tr(i, 20000, 19000 + i * 10, i % 3 == 0 ? WIN : LOSE));
        }
        HighlightStats h = new HighlightStatCalculator().calculate(new AnalyticsContext(matches));
        MetricSamples.StrengthSlices s = MetricSamples.strengthSlices(matches);

        assertThat(s.q1().size()).isEqualTo(h.strengthQuintiles().get(0).matches());
        assertThat(s.q5().size()).isEqualTo(h.strengthQuintiles().get(4).matches());
        double q5 = s.q5().stream().filter(MatchHistory::isWin).count() / (double) s.q5().size();
        double q1 = s.q1().stream().filter(MatchHistory::isWin).count() / (double) s.q1().size();
        assertThat(q5 - q1).isCloseTo(h.strengthSplit(), within(TOL));
    }

    @Test
    void TR_있는_매치가_5판_미만이면_Q1_Q5는_비어_있다() {
        MetricSamples.StrengthSlices s = MetricSamples.strengthSlices(List.of(tr(0, 1, 2, WIN), tr(1, 1, 2, LOSE)));

        assertThat(s.q1()).isEmpty();
        assertThat(s.q5()).isEmpty();
    }

    @Test
    void 배열_기울기는_sessionVsSlope와_같고_조기_종료_마지막_라운드를_뺀다() {
        List<MatchHistory> matches = List.of(vsRounds(10, 20, 30), vsRounds(30, 45, 50, 41),
                vsRoundsEndedEarly(30, 40, 50, 0), vsRounds(12, 9));
        List<MetricSamples.RoundSeries> rounds = MetricSamples.roundSeries(matches);

        assertThat(MetricSamples.slopeOf(rounds)).isEqualTo(HighlightStatCalculator.sessionVsSlope(matches));
        assertThat(MetricSamples.distinctRoundIndices(rounds)).isEqualTo(4);
        assertThat(rounds.get(2).roundIndex().length).isEqualTo(3);
    }

    @Test
    void 라운드_순번이_1종류면_기울기는_null이다() {
        assertThat(MetricSamples.slopeOf(MetricSamples.roundSeries(List.of(vsRounds(10), vsRounds(50))))).isNull();
        assertThat(MetricSamples.slopeOf(List.of())).isNull();
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }
}