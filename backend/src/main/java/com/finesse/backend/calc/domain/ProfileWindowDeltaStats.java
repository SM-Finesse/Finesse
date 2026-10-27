package com.finesse.backend.calc.domain;

/**
 *  최근 N판 vs 나머지의 변화율 % (설계서 11.11절, v3.10).
 *  나머지 구간이 비면 5개 필드가 모두 null이다(분석 판수가 10판 이상이면 발생하지 않음).
 *  예외로 trDeltaPct는 어느 한 구간에 매치 당시 TR이 하나도 없으면 단독으로 null일 수 있다.
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