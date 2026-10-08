package com.finesse.backend.calc.domain;

/**
 * 직전 구간 대비 변화율 % (설계서 11.11절).
 * 이전 구간이 없으면 5개 필드가 모두 null이다.
 * 예외로 trDeltaPct는 어느 한 구간에 매치 당시 TR이 하나도 없으면 단독으로 null일 수 있다.
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
        return wrDeltaPct != null;
    }
}