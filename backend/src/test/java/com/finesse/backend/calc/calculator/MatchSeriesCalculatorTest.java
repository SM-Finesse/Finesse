package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchSeriesStats;
import com.finesse.backend.calc.domain.MatchSeriesStats.RoundPoint;
import com.finesse.backend.calc.domain.MatchSeriesStats.TrPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 경기별 시계열 (설계서 11.13절, 테스트 계획 49.3 #10·#11). */
class MatchSeriesCalculatorTest {

    private static final double TOL = 1e-9;
    private final MatchSeriesCalculator calculator = new MatchSeriesCalculator();

    private MatchSeriesStats calc(List<MatchHistory> matches) {
        return calculator.calculate(new AnalyticsContext(matches));
    }

    @Test
    void TR_시계열은_오래된_매치부터_최근_매치_순이고_TR_없는_매치는_뺀다() {
        MatchSeriesStats s = calc(List.of(
                tr(30, 1200, 1100, WIN),
                withoutTr(20, LOSE),
                tr(10, 1000, 1100, LOSE),
                tr(20, 1100, 1100, WIN)));

        assertThat(s.trSeries()).extracting(TrPoint::tr).containsExactly(1000.0, 1100.0, 1200.0);
        assertThat(s.trSeries()).extracting(TrPoint::playedAt)
                .containsExactly(BASE.plusSeconds(10), BASE.plusSeconds(20), BASE.plusSeconds(30));
    }

    @Test
    void 라운드_곡선은_라운드_순서별_평균_PPS와_VS다() {
        // 라운드 1: PPS (1.0+2.0)/2, VS (10+30)/2 / 라운드 2: PPS 3.0만(다른 쪽 결측), VS (20+40)/2
        MatchSeriesStats s = calc(List.of(
                ppsVsRounds(false, new Double[]{1.0, 3.0}, 10, 20),
                ppsVsRounds(false, new Double[]{2.0, null}, 30, 40)));

        assertThat(s.roundCurve()).hasSize(2);
        RoundPoint r1 = s.roundCurve().get(0);
        RoundPoint r2 = s.roundCurve().get(1);
        assertThat(r1.round()).isEqualTo(1);
        assertThat(r1.avgPps()).isCloseTo(1.5, within(TOL));
        assertThat(r1.avgVs()).isCloseTo(20.0, within(TOL));
        assertThat(r1.samples()).isEqualTo(2);
        assertThat(r2.round()).isEqualTo(2);
        assertThat(r2.avgPps()).isCloseTo(3.0, within(TOL));
        assertThat(r2.avgVs()).isCloseTo(30.0, within(TOL));
    }

    @Test
    void 조기_종료_매치는_마지막_라운드를_빼고_PPS가_하나도_없으면_null이다() {
        MatchSeriesStats s = calc(List.of(
                ppsVsRounds(true, new Double[]{null, null, 0.1}, 10, 20, 0)));   // 라운드 3은 이탈로 끊김

        assertThat(s.roundCurve()).extracting(RoundPoint::round).containsExactly(1, 2);
        assertThat(s.roundCurve().get(0).avgPps()).isNull();
    }

    @Test
    void 매치가_없으면_빈_목록이다() {
        MatchSeriesStats s = calc(List.of());

        assertThat(s.trSeries()).isEmpty();
        assertThat(s.roundCurve()).isEmpty();
    }
}
