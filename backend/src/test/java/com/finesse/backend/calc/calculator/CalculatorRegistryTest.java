package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.FancyStats;
import com.finesse.backend.calc.exception.CalculatorNotRegisteredException;
import com.finesse.backend.calc.exception.DuplicateCalculatorKeyException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.MatchFixtures.match;
import static org.assertj.core.api.Assertions.*;

class CalculatorRegistryTest {

    @Test
    void 등록된_Calculator를_key로_찾아_실행한다() {
        CalculatorRegistry registry = new CalculatorRegistry(List.of(new FancyMathCalculator()));

        FancyStats stats = registry.calculate(CalculatorKey.FANCY,
                new AnalyticsContext(List.of(match(60, 1.0, 120)), null));

        assertThat(stats.sampleCount()).isEqualTo(1);
    }

    @Test
    void 같은_key가_두_번_등록되면_생성_시점에_실패한다() {
        assertThatThrownBy(() -> new CalculatorRegistry(
                List.of(new FancyMathCalculator(), new FancyMathCalculator())))
                .isInstanceOf(DuplicateCalculatorKeyException.class);
    }

    @Test
    void 등록되지_않은_key를_조회하면_예외를_던진다() {
        CalculatorRegistry registry = new CalculatorRegistry(List.of(new FancyMathCalculator()));

        assertThatThrownBy(() -> registry.calculate(CalculatorKey.DELTA,
                new AnalyticsContext(List.of(), null)))
                .isInstanceOf(CalculatorNotRegisteredException.class);
    }
}