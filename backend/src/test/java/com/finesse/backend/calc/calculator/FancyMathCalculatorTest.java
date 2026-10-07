package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.FancyStats;
import com.finesse.backend.calc.domain.MatchHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.MatchFixtures.match;
import static org.assertj.core.api.Assertions.*;

class FancyMathCalculatorTest {

    private static final double TOL = 1e-4; // 설계서 21.2절: 소수점 4자리 일치
    private final FancyMathCalculator calculator = new FancyMathCalculator();

    private FancyStats calc(MatchHistory... matches) {
        return calculator.calculate(new AnalyticsContext(List.of(matches), null));
    }

    @Test
    void 단일_매치의_6개_지표가_손계산값과_일치한다() {
        // APM 60, PPS 1.0, VS 120
        // APP    = 60 / (1.0*60)            = 1.0
        // VS/APM = 120 / 60                 = 2.0
        // DS/S   = 1.2 - 1.0                = 0.2
        // DS/P   = 0.2 / 1.0                = 0.2
        // Cheese = 0.2*150 + 0*50 + (-0.4)*125 = -20.0
        // GbE    = (1.0*0.2 / 1.0) * 2      = 0.4
        // wAPP   = 1.0 - 5*tan(1.6667°)     = 0.8545
        FancyStats s = calc(match(60, 1.0, 120));

        assertThat(s.sampleCount()).isEqualTo(1);
        assertThat(s.app()).isCloseTo(1.0, within(TOL));
        assertThat(s.vsApm()).isCloseTo(2.0, within(TOL));
        assertThat(s.dsS()).isCloseTo(0.2, within(TOL));
        assertThat(s.cheeseIndex()).isCloseTo(-20.0, within(TOL));
        assertThat(s.gbE()).isCloseTo(0.4, within(TOL));
        assertThat(s.weightedApp()).isCloseTo(0.8545, within(TOL));
    }

    @Test
    void 여러_매치는_매치별_값을_산술평균한다() {
        // 두 번째 매치: APM 90, PPS 1.5, VS 150
        //   APP 1.0 / VS/APM 1.6667 / DS/S 0.0 / Cheese -66.6667 / GbE 0.0 / wAPP 0.7185
        FancyStats s = calc(match(60, 1.0, 120), match(90, 1.5, 150));

        assertThat(s.sampleCount()).isEqualTo(2);
        assertThat(s.avgApm()).isCloseTo(75.0, within(TOL));
        assertThat(s.avgPps()).isCloseTo(1.25, within(TOL));
        assertThat(s.avgVs()).isCloseTo(135.0, within(TOL));
        assertThat(s.app()).isCloseTo(1.0, within(TOL));
        assertThat(s.vsApm()).isCloseTo(1.8333, within(TOL));
        assertThat(s.dsS()).isCloseTo(0.1, within(TOL));
        assertThat(s.cheeseIndex()).isCloseTo(-43.3333, within(TOL));
        assertThat(s.gbE()).isCloseTo(0.2, within(TOL));
        assertThat(s.weightedApp()).isCloseTo(0.7865, within(TOL));
    }

    @Test
    void APM이_0이거나_PPS가_0점1_미만인_매치는_평균에서_제외한다() {
        FancyStats withNoise = calc(match(60, 1.0, 120), match(0, 1.0, 120), match(60, 0.05, 120));
        FancyStats clean = calc(match(60, 1.0, 120));

        assertThat(withNoise.sampleCount()).isEqualTo(1);
        assertThat(withNoise).usingRecursiveComparison()
                .ignoringFields("sampleCount").isEqualTo(clean);
    }

    @Test
    void 계산_가능한_매치가_없으면_null을_반환한다() {
        // APM = 0·PPS ≥ 0.2 매치는 정제를 통과하지만 평균에서는 빠진다 (6.5절·7장, v3.5)
        assertThat(calc(match(0, 1.0, 120))).isNull();
    }

    @Test
    void tan_발산_구간에서는_WeightedAPP를_APP_원값으로_대체한다() {
        // cheese = -2670 → (-2670 / -30) + 1 = 90° → tan 발산
        assertThat(FancyMathCalculator.weightedApp(1.2, -2670.0)).isEqualTo(1.2);
    }

    @Test
    void 같은_입력은_항상_같은_결과를_낸다() {
        List<MatchHistory> input = List.of(match(60, 1.0, 120), match(90, 1.5, 150));
        FancyStats first = calculator.calculate(new AnalyticsContext(input, null));
        FancyStats second = calculator.calculate(new AnalyticsContext(input, null));

        assertThat(first).isEqualTo(second);
    }
}