package com.finesse.backend.calc.exception;

import com.finesse.backend.calc.calculator.CalculatorKey;

public class CalculatorNotRegisteredException extends RuntimeException {
    public CalculatorNotRegisteredException(CalculatorKey key) {
        super("등록되지 않은 CalculatorKey: " + key);
    }
}