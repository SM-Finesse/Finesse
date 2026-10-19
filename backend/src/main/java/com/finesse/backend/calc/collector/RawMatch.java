package com.finesse.backend.calc.collector;

import java.time.Instant;
import java.util.List;

/**
 * TETR.IO 랭크 매치 레코드 1건에서 분석에 필요한 값만 뽑은 원천 데이터 (설계서 부록 B).
 * JSON → RawMatch 파싱은 Collector 책임이며, 본인/상대 구분은 otherusers[].id 기준이다.
 *
 * <pre>
 * matchId        ← _id
 * playedAt       ← ts
 * result         ← extras.result ("victory" | "defeat")
 * me / opponent  ← results.leaderboard[] (otherusers[].id에 있는 쪽이 상대)
 * rounds         ← results.rounds[]
 * </pre>
 */
public record RawMatch(
        String matchId,
        Instant playedAt,
        String result,
        RawPlayer me,
        RawPlayer opponent,
        List<RawRound> rounds
) {
    public RawMatch {
        rounds = rounds == null ? null : List.copyOf(rounds);
    }

    /**
     * @param userId          leaderboard[].id
     * @param usernameAtMatch leaderboard[].username (매치 당시 닉네임)
     * @param apm             leaderboard[].stats.apm
     * @param pps             leaderboard[].stats.pps
     * @param vs              leaderboard[].stats.vsscore
     * @param trBefore        extras.league[userId][0].tr (매치 시작 시점 TR, 없으면 null)
     * @param rankBefore      extras.league[userId][0].rank (매치 시작 시점 등급, 소문자. 없으면 null) — 5.11절
     */
    public record RawPlayer(
            String userId,
            String usernameAtMatch,
            Double apm,
            Double pps,
            Double vs,
            Double trBefore,
            String rankBefore
    ) {}

    /**
     * results.rounds[] 원소 1개 — 라운드 종료 시 alive가 true인 쪽이 라운드 승자다.
     */
    public record RawRound(
            boolean meAlive,
            boolean opponentAlive,
            Double myVs,
            Double opponentVs,
            Double myPps        // 라운드 본인 stats.pps, 없으면 null (v3.6, 라운드 곡선용)
    ) {}
}