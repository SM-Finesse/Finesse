package com.finesse.backend.calc.collector;

/**
 * GET /users/{username}/summaries/league 응답 (설계서 3.2절).
 * TR은 조회 시점의 현재 TR이며 매치 단위 계산에는 쓰지 않는다(1.1절 ⑧).
 */
public record UserSummary(
        String username,
        String rank,
        double tr,
        double glicko,
        double rd,
        Double gxe,
        int gamesPlayed
) {}