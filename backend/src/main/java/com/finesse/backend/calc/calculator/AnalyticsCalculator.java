package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;

/**
 * 모든 Calculator의 공통 계약 (설계서 19.3절).
 * 구현체는 요청 간 상태를 인스턴스 필드에 저장하지 않는다 (1.1절 ⑤ Stateless).
 */
public interface AnalyticsCalculator<T> {
    CalculatorKey key();
    T calculate(AnalyticsContext context);
}