package com.finesse.backend.calc.exception;

public enum AnalyticsErrorCode {
    ANALYTICS_NO_MATCH,        // 입력 매치 목록 자체가 비어 있음
    ANALYTICS_NO_VALID_MATCH   // 매치는 있으나 계산 가능한 매치가 0개
}