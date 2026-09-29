package com.finesse.backend.model;

/**
 * 라운드 1건의 내 스탯 vs 상대 스탯. rounds 배열은 실제 플레이 순서라고 검증됨
 * (데이터 수집 명세 5장).
 *
 * ※ 임시 구현 — 데이터팀(위성훈, data-eng 브랜치)의 MatchRound(com.finesse.backend.calc.domain)로
 * 교체 예정 (2026-09-29 팀 확인).
 */
public record RoundSample(int index, boolean meAlive, boolean oppAlive, Sample me, Sample opp) {
    public record Sample(Double apm, Double pps, Double vs) {
    }
}
