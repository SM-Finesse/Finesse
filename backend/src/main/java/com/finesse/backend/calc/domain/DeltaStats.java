package com.finesse.backend.calc.domain;

/**
 * 본인 − 상대 스탯 차이의 매치 평균 (설계서 7·8장).
 * sampleCount는 기본 Δ 평균에 쓰인 매치 수다.
 * 플레이스타일 Δ(Opener·Plonk·Stride·Inf DS)는 StatrankCurve 연동 전까지 null이다(8장).
 */
public record DeltaStats(
        int sampleCount,
        double deltaPps,
        double deltaApm,
        double deltaVs,
        double deltaApp,
        Double deltaOpener,
        Double deltaPlonk,
        Double deltaStride,
        Double deltaInfDs
) {}