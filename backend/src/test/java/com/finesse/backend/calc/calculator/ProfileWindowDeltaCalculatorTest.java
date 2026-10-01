package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProfileWindowDeltaCalculatorTest {

    private static final double TOL = 1e-9;
    private final ProfileWindowDeltaCalculator calculator = new ProfileWindowDeltaCalculator();

    @Test
    void 구간별_평균의_변화율을_계산하고_이전값이_0이면_0으로_처리한다() {
        // 현재: APM 70 / PPS 1.1 / VS 130 / TR 1050 / 승률 50%
        List<MatchHistory> current = List.of(
                stats(60, 1.0, 120, 1000, WIN),
                stats(80, 1.2, 140, 1100, LOSE));
        // 이전: APM 50 / PPS 1.0 / VS 100 / TR 1000 / 승률 0%
        List<MatchHistory> previous = List.of(
                stats(50, 1.0, 100, 1000, LOSE),
                stats(50, 1.0, 100, 1000, LOSE));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(current, previous));

        assertThat(s.isAvailable()).isTrue();
        assertThat(s.trDeltaPct()).isCloseTo(5.0, within(TOL));   // (1050-1000)/1000*100
        assertThat(s.apmDeltaPct()).isCloseTo(40.0, within(TOL)); // (70-50)/50*100
        assertThat(s.ppsDeltaPct()).isCloseTo(10.0, within(TOL)); // (1.1-1.0)/1.0*100
        assertThat(s.vsDeltaPct()).isCloseTo(30.0, within(TOL));  // (130-100)/100*100
        assertThat(s.wrDeltaPct()).isCloseTo(0.0, within(TOL));   // 이전 승률 0% → 분모 0 방어
    }

    @Test
    void 승률_변화율은_퍼센트_기준으로_계산한다() {
        // 현재 3승 1패 = 75%, 이전 1승 3패 = 25% → (75-25)/25*100 = 200%
        List<MatchHistory> current = List.of(
                stats(60, 1, 120, 1000, WIN), stats(60, 1, 120, 1000, WIN),
                stats(60, 1, 120, 1000, WIN), stats(60, 1, 120, 1000, LOSE));
        List<MatchHistory> previous = List.of(
                stats(60, 1, 120, 1000, WIN), stats(60, 1, 120, 1000, LOSE),
                stats(60, 1, 120, 1000, LOSE), stats(60, 1, 120, 1000, LOSE));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(current, previous));

        assertThat(s.wrDeltaPct()).isCloseTo(200.0, within(TOL));
    }

    @Test
    void 이전_구간이_없으면_5개_필드가_모두_null이다() {
        List<MatchHistory> current = List.of(stats(60, 1.0, 120, 1000, WIN));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(current, null));

        assertThat(s.isAvailable()).isFalse();
        assertThat(s).isEqualTo(ProfileWindowDeltaStats.unavailable());
    }

    @Test
    void 한_구간에_매치_당시_TR이_없으면_TR_변화율만_null이다() {
        List<MatchHistory> current = List.of(withoutTr(20, WIN), withoutTr(21, LOSE));
        List<MatchHistory> previous = List.of(stats(50, 1.0, 100, 1000, LOSE), stats(50, 1.0, 100, 1000, WIN));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(current, previous));

        assertThat(s.isAvailable()).isTrue();
        assertThat(s.trDeltaPct()).isNull();
        assertThat(s.apmDeltaPct()).isCloseTo(20.0, within(TOL));   // (60−50)/50×100
        assertThat(s.wrDeltaPct()).isCloseTo(0.0, within(TOL));
    }
}