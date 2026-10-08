package com.finesse.backend.calc.exception;

import com.finesse.backend.calc.calculator.CalculatorKey;

public class DuplicateCalculatorKeyException extends RuntimeException {
    public DuplicateCalculatorKeyException(CalculatorKey key) {
        super("같은 CalculatorKey를 가진 Calculator가 2개 이상 등록됨: " + key);
    }
}