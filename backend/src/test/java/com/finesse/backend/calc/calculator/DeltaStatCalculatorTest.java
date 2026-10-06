package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.MatchHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.MatchFixtures.delta;
import static org.assertj.core.api.Assertions.*;

class DeltaStatCalculatorTest {

    private static final double TOL = 1e-9;
    private static final double TOL4 = 1e-4; // tan이 들어가는 Weighted APP은 소수점 4자리 비교
    private static final double TOL6 = 1e-5; // 플레이스타일 손계산값(소수점 6자리) 비교
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
    void 공격_수비_하이라이트_Delta를_FancyMath_공식으로_계산한다() {
        // 본인 APM 60 / PPS 1.0 / VS 120 → VS/APM 2.0, Cheese −20.0,  wAPP 0.8545
        // 상대 APM 30 / PPS 1.0 / VS 60  → VS/APM 2.0, Cheese  27.5,  wAPP 0.4927
        //   (상대 DS/S = 0.6 − 0.5 = 0.1 → Cheese = 0.1×150 + 0×50 + 0.1×125 = 27.5)
        DeltaStats s = calc(delta(60, 1.0, 120, 30, 1.0, 60));

        assertThat(s.deltaVsApm()).isCloseTo(0.0, within(TOL));
        assertThat(s.deltaCheeseIndex()).isCloseTo(-47.5, within(TOL));
        assertThat(s.deltaWeightedApp()).isCloseTo(0.3618, within(TOL4));
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
        // 매치2: 본인 Cheese −66.6667 / wAPP 0.7185, 상대 (90, 1.0, 150) Cheese −129.1667 / wAPP 1.0357
        //   ΔCheese: (−47.5 + 62.5) / 2 = 7.5, ΔwAPP: (0.3618 − 0.3172) / 2 = 0.0223
        assertThat(s.deltaVsApm()).isCloseTo(0.0, within(TOL));
        assertThat(s.deltaCheeseIndex()).isCloseTo(7.5, within(TOL));
        assertThat(s.deltaWeightedApp()).isCloseTo(0.0223, within(TOL4));
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
    void 계산_가능한_매치가_없으면_null을_반환한다() {
        // APM = 0·PPS ≥ 0.2 매치는 정제를 통과하지만 평균에서는 빠진다 (6.5절·7장, v3.5)
        assertThat(calc(delta(60, 1.0, 120, 0, 1.0, 1))).isNull();
    }

    @Test
    void 플레이스타일_Delta를_공식으로_계산한다() {
        // 본인 (150, 3.0, 330): srArea 833.3333, statrank 16.6461
        //   → Opener 0.457591, Plonk 0.929077, Stride 0.060532, Inf DS 0.729619
        // 상대 (120, 2.5, 260): srArea 737.5, statrank 16.3637
        //   → Opener 0.473086, Plonk 0.907894, Stride 0.006294, Inf DS 0.596805
        DeltaStats s = calc(delta(150, 3.0, 330, 120, 2.5, 260));

        assertThat(s.deltaOpener()).isCloseTo(0.457591 - 0.473086, within(TOL6));
        assertThat(s.deltaPlonk()).isCloseTo(0.929077 - 0.907894, within(TOL6));
        assertThat(s.deltaStride()).isCloseTo(0.060532 - 0.006294, within(TOL6));
        assertThat(s.deltaInfDs()).isCloseTo(0.729619 - 0.596805, within(TOL6));
    }

    @Test
    void 플레이스타일을_계산할_수_있는_매치가_절반_미만이면_null이다() {
        // 상대 (120, 1.0, 10): DS/P = 0.1 − 2.0 = −1.9 → srArea = 135 + 580 − 1330 < 0 → 계산 불가
        DeltaStats mostlyBroken = calc(
                delta(150, 3.0, 330, 120, 2.5, 260),
                delta(150, 3.0, 330, 120, 1.0, 10),
                delta(150, 3.0, 330, 120, 1.0, 10));
        DeltaStats mostlyFine = calc(
                delta(150, 3.0, 330, 120, 2.5, 260),
                delta(150, 3.0, 330, 120, 2.5, 260),
                delta(150, 3.0, 330, 120, 1.0, 10));

        assertThat(mostlyBroken.deltaOpener()).isNull();
        assertThat(mostlyBroken.deltaInfDs()).isNull();
        assertThat(mostlyFine.deltaOpener()).isCloseTo(0.457591 - 0.473086, within(TOL6)); // 계산 가능한 2판만 평균
    }
}