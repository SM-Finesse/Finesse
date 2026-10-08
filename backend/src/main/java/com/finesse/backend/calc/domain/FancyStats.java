package com.finesse.backend.calc.domain;

/**
 * FancyMathCalculator 결과 — 계산 가능한 매치별 값을 산술 평균한 것 (설계서 6장).
 * sampleCount는 APM=0, PPS<0.1 매치를 제외하고 실제 평균에 쓰인 매치 수다.
 */
public record FancyStats(
        int sampleCount,
        double avgApm,
        double avgPps,
        double avgVs,
        double app,
        double vsApm,
        double dsS,
        double cheeseIndex,
        double gbE,
        double weightedApp
) {}