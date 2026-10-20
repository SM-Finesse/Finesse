package com.finesse.backend.calc.collector;

/**
 * GET /users/{username}/summaries/league 응답 (설계서 3.2절, 응답 필드 전체는 02 부록 C).
 * TR은 조회 시점의 현재 TR이며 매치 단위 계산에는 쓰지 않는다(1.1절 ⑧).
 * apm·pps·vs도 조회 시점의 리그 평균으로, 프로필 표시에만 쓴다. 응답에 없으면 null (v3.6).
 * gamesPlayed·gamesWon·gamesLost()는 현재 시즌 리그 기준이다. 응답에 gameswon이 없으면 gamesWon은 0 (v4.1).
 */
public record UserSummary(
        String username,
        String rank,
        double tr,
        double glicko,
        double rd,
        Double gxe,
        int gamesPlayed,
        int gamesWon,
        Double apm,
        Double pps,
        Double vs
) {
    /**
     * v4.0까지의 생성자(gamesWon 없음) — 기존 호출 코드를 깨지 않도록 남긴다. gamesWon은 0으로 둔다.
     */
    public UserSummary(String username, String rank, double tr, double glicko, double rd, Double gxe,
                       int gamesPlayed, Double apm, Double pps, Double vs) {
        this(username, rank, tr, glicko, rd, gxe, gamesPlayed, 0, apm, pps, vs);
    }

    /** 현재 시즌 리그 패수 = gamesPlayed − gamesWon. API에 패수 필드가 없어 계산하며, 0 미만이면 0 (v4.1). */
    public int gamesLost() {
        return Math.max(0, gamesPlayed - gamesWon);
    }
}