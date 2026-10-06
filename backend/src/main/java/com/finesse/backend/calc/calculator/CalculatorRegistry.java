package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.exception.CalculatorNotRegisteredException;
import com.finesse.backend.calc.exception.DuplicateCalculatorKeyException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AnalyticsCalculator 구현체를 Spring이 모두 주입하면 key별로 보관한다 (설계서 19.4절).
 * 같은 key가 두 번 등록되면 기동 시점에 바로 실패한다.
 */
@Component
public class CalculatorRegistry {

    private final Map<CalculatorKey, AnalyticsCalculator<?>> calculators;

    public CalculatorRegistry(List<AnalyticsCalculator<?>> calculators) {
        Map<CalculatorKey, AnalyticsCalculator<?>> map = new EnumMap<>(CalculatorKey.class);
        for (AnalyticsCalculator<?> calculator : calculators) {
            if (map.putIfAbsent(calculator.key(), calculator) != null) {
                throw new DuplicateCalculatorKeyException(calculator.key());
            }
        }
        this.calculators = Map.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    public <T> T calculate(CalculatorKey key, AnalyticsContext context) {
        AnalyticsCalculator<T> calculator = (AnalyticsCalculator<T>) calculators.get(key);
        if (calculator == null) {
            throw new CalculatorNotRegisteredException(key);
        }
        return calculator.calculate(context);
    }

    public boolean isRegistered(CalculatorKey key) {
        return calculators.containsKey(key);
    }
}