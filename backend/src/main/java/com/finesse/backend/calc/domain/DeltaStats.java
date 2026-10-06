package com.finesse.backend.calc.domain;

/**
 * 본인 − 상대 스탯 차이의 매치 평균 (설계서 7·8장). 양수면 본인 우위.
 * sampleCount는 Δ 평균에 쓰인 매치 수다.
 * LLM 하이라이트 후보: deltaApp·deltaWeightedApp(attack), deltaVsApm·deltaCheeseIndex(defense), 플레이스타일 4종.
 * deltaPps·deltaApm·deltaVs는 후보가 아닌 차트용 원시값이다.
 * 플레이스타일 Δ(Opener·Plonk·Stride·Inf DS)는 StatrankCurve 연동 전까지 null이다(8장).
 */
public record DeltaStats(
        int sampleCount,
        double deltaPps,
        double deltaApm,
        double deltaVs,
        double deltaApp,
        double deltaWeightedApp,
        double deltaVsApm,
        double deltaCheeseIndex,
        Double deltaOpener,
        Double deltaPlonk,
        Double deltaStride,
        Double deltaInfDs
) {}