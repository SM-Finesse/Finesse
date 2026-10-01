package com.finesse.backend.calc.fixture;

import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.collector.RawMatch.RawPlayer;
import com.finesse.backend.calc.collector.RawMatch.RawRound;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 테스트용 RawMatch 생성 도우미. 유저 ID·닉네임은 모두 가상의 값이다.
 */
public final class RawMatchFixtures {

    public static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    private RawMatchFixtures() {}

    public static RawPlayer player(String userId, String nickname, Double apm, Double pps, Double vs, Double tr) {
        return new RawPlayer(userId, nickname, apm, pps, vs, tr);
    }

    public static RawPlayer me() {
        return player("uid-me", "me", 60.0, 1.0, 120.0, 1000.0);
    }

    public static RawPlayer opponent(String userId, String nickname) {
        return player(userId, nickname, 50.0, 1.0, 100.0, 1100.0);
    }

    /** 라운드 결과를 순서대로 지정 (true = 본인 승) */
    public static List<RawRound> rounds(boolean... myWins) {
        return IntStream.range(0, myWins.length)
                .mapToObj(i -> new RawRound(myWins[i], !myWins[i], 100.0 + i, 90.0 + i))
                .toList();
    }

    public static RawMatch raw(String matchId, int secondsFromBase, String result,
                               RawPlayer me, RawPlayer opponent, List<RawRound> rounds) {
        return new RawMatch(matchId, BASE.plusSeconds(secondsFromBase), result, me, opponent, rounds);
    }

    /** 정상 매치: 본인 승(라운드 W W) */
    public static RawMatch valid(String matchId, int secondsFromBase, String opponentUserId, String opponentNickname) {
        return raw(matchId, secondsFromBase, "victory", me(), opponent(opponentUserId, opponentNickname),
                rounds(true, true));
    }
}