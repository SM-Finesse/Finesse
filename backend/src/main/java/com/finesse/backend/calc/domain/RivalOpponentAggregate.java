package com.finesse.backend.calc.domain;

/**
 * 반복 조우 상대 1인 집계 — 모듈 내부 전용 (설계서 11.12절).
 * opponentId(PseudonymId)를 포함하므로 모듈 밖으로 내보내지 않는다. 외부 노출은 RivalOpponentStats.
 * winRate는 % 단위다(0~100).
 */
public record RivalOpponentAggregate(
        PseudonymId opponentId,
        int matchCount,
        int wins,
        int losses,
        double winRate,
        RivalBadge badge
) {}