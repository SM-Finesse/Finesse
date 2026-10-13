package com.finesse.backend.calc.domain;

/**
 * 본인 − 상대 스탯 차이의 매치 평균 (설계서 7·8장). 양수면 본인 우위.
 * sampleCount는 Δ 평균에 쓰인 매치 수다.
 * LLM 하이라이트 후보: deltaApp·deltaWeightedApp(attack), deltaVsApm·deltaCheeseIndex(defense), 플레이스타일 4종.
 * deltaPps·deltaApm·deltaVs는 후보가 아닌 차트용 원시값이다.
 * 플레이스타일 Δ(Opener·Plonk·Stride·Inf DS)는 계산 가능한 매치가 50% 미만이면 null이다(8장).
 * mine·opp는 Δ와 같은 매치 집합(sampleCount판)에서 낸 본인·상대 평균이다. 각 지표는 mine − opp = Δ다 (7.4절, v4.0).
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
        Double deltaInfDs,
        StatAverages mine,
        StatAverages opp
) {
    /**
     * 한 선수(본인 또는 상대)의 매치 평균 (7.4절, v4.0) — 공격 효율·수비 챕터의 “나 vs 상대 평균” 표시용.
     *
     * @param apm         평균 APM
     * @param pps         평균 PPS
     * @param vs          평균 VS
     * @param app         평균 APP (블록당 공격량)
     * @param weightedApp 평균 Weighted APP
     * @param vsApm       평균 VS/APM
     * @param cheeseIndex 평균 Cheese Index
     */
    public record StatAverages(
            double apm,
            double pps,
            double vs,
            double app,
            double weightedApp,
            double vsApm,
            double cheeseIndex
    ) {}
}