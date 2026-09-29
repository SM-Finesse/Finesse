package com.finesse.backend.calc.domain;

/**
 * 직전 구간 대비 변화율 % (설계서 11.11절).
 * 이전 구간이 없으면 5개 필드가 모두 null이다. 일부만 null인 경우는 없다.
 */
public record ProfileWindowDeltaStats(
        Double trDeltaPct,
        Double wrDeltaPct,
        Double apmDeltaPct,
        Double ppsDeltaPct,
        Double vsDeltaPct
) {
    public static ProfileWindowDeltaStats unavailable() {
        return new ProfileWindowDeltaStats(null, null, null, null, null);
    }

    public boolean isAvailable() {
        return trDeltaPct != null;
    }
}