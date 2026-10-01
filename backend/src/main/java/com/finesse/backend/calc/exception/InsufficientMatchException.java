package com.finesse.backend.calc.exception;

public class InsufficientMatchException extends RuntimeException {

    private final AnalyticsErrorCode errorCode;
    private final int actualCount;
    private final int requiredCount;

    public InsufficientMatchException(AnalyticsErrorCode errorCode, int actualCount, int requiredCount) {
        super("매치 표본 부족: actual=%d, required=%d".formatted(actualCount, requiredCount));
        this.errorCode = errorCode;
        this.actualCount = actualCount;
        this.requiredCount = requiredCount;
    }

    public AnalyticsErrorCode errorCode() { return errorCode; }
    public int actualCount() { return actualCount; }
    public int requiredCount() { return requiredCount; }
}