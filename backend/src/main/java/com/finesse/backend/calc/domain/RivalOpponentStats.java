package com.finesse.backend.calc.domain;

/**
 * 반복 조우 상대 1인 — 외부 노출용 (설계서 11.12절).
 * 원본 닉네임·유저 ID·PseudonymId를 포함하지 않는다. winRate는 % 단위다(0~100).
 */
public record RivalOpponentStats(
        String maskedNickname,
        int matchCount,
        int wins,
        int losses,
        double winRate,
        RivalBadge badge
) {}