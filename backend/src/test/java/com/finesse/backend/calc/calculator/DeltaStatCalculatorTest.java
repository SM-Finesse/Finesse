package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.exception.InsufficientMatchException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.MatchFixtures.delta;
import static org.assertj.core.api.Assertions.*;

class DeltaStatCalculatorTest {

    private static final double TOL = 1e-9;
    private final DeltaStatCalculator calculator = new DeltaStatCalculator();

    private DeltaStats calc(MatchHistory... matches) {
        return calculator.calculate(new AnalyticsContext(List.of(matches), null));
    }

    @Test
    void 단일_매치의_기본_Delta를_계산한다() {
        // 본인 APM 60 / PPS 1.0 / VS 120 → APP 1.0
        // 상대 APM 30 / PPS 1.0 / VS 60  → APP 0.5
        DeltaStats s = calc(delta(60, 1.0, 120, 30, 1.0, 60));

        assertThat(s.sampleCount()).isEqualTo(1);
        assertThat(s.deltaPps()).isCloseTo(0.0, within(TOL));
        assertThat(s.deltaApm()).isCloseTo(30.0, within(TOL));
        assertThat(s.deltaVs()).isCloseTo(60.0, within(TOL));
        assertThat(s.deltaApp()).isCloseTo(0.5, within(TOL));
    }

    @Test
    void 매치별_Delta를_산술평균한다() {
        // 매치1: ΔPPS 0,   ΔAPM 30, ΔVS 60, ΔAPP +0.5
        // 매치2: ΔPPS 0.5, ΔAPM 0,  ΔVS 0,  ΔAPP 1.0 − 1.5 = −0.5
        DeltaStats s = calc(delta(60, 1.0, 120, 30, 1.0, 60), delta(90, 1.5, 150, 90, 1.0, 150));

        assertThat(s.sampleCount()).isEqualTo(2);
        assertThat(s.deltaPps()).isCloseTo(0.25, within(TOL));
        assertThat(s.deltaApm()).isCloseTo(15.0, within(TOL));
        assertThat(s.deltaVs()).isCloseTo(30.0, within(TOL));
        assertThat(s.deltaApp()).isCloseTo(0.0, within(TOL));
    }

    @Test
    void 본인이나_상대의_APM이_0이거나_PPS가_0점1_미만이면_제외한다() {
        DeltaStats withNoise = calc(
                delta(60, 1.0, 120, 30, 1.0, 60),
                delta(60, 1.0, 120, 0, 1.0, 120),      // 상대 APM 0
                delta(60, 1.0, 120, 60, 0.05, 120));   // 상대 PPS 0.05

        assertThat(withNoise).isEqualTo(calc(delta(60, 1.0, 120, 30, 1.0, 60)));
    }

    @Test
    void 계산_가능한_매치가_없으면_예외를_던진다() {
        assertThatThrownBy(() -> calc(delta(60, 1.0, 120, 0, 1.0, 1)))
                .isInstanceOf(InsufficientMatchException.class);
    }

    @Test
    void 플레이스타일_Delta는_StatrankCurve_연동_전까지_null이다() {
        DeltaStats s = calc(delta(60, 1.0, 120, 30, 1.0, 60));

        assertThat(s.deltaOpener()).isNull();
        assertThat(s.deltaPlonk()).isNull();
        assertThat(s.deltaStride()).isNull();
        assertThat(s.deltaInfDs()).isNull();
    }
}