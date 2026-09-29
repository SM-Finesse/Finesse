package com.finesse.backend.model;

/**
 * 라운드 1건의 내 스탯 vs 상대 스탯. rounds 배열은 실제 플레이 순서라고 검증됨
 * (데이터 수집 명세 5장).
 */
public record RoundSample(int index, boolean meAlive, boolean oppAlive, Sample me, Sample opp) {
    public record Sample(Double apm, Double pps, Double vs) {
    }
}
