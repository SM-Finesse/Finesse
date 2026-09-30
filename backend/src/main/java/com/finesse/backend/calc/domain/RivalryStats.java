package com.finesse.backend.calc.domain;

import java.util.List;
import java.util.function.Function;

/**
 * 반복 조우 상대 통계 — 외부 노출용 (설계서 11.12절).
 */
public record RivalryStats(
        int rivalCount,
        RivalOpponentStats nemesis,
        RivalOpponentStats dominant,
        List<RivalOpponentStats> rivals
) {
    public RivalryStats {
        rivals = List.copyOf(rivals);
    }

    public static RivalryStats empty() {
        return new RivalryStats(0, null, null, List.of());
    }

    /**
     * 내부 집계에 마스킹 닉네임을 붙여 외부 노출용으로 변환한다.
     * maskedNicknameResolver는 Facade가 PseudonymScope로 만들어 넘긴다.
     */
    public static RivalryStats from(RivalryAggregate aggregate,
                                    Function<PseudonymId, String> maskedNicknameResolver) {
        Function<RivalOpponentAggregate, RivalOpponentStats> toStats = a -> a == null ? null
                : new RivalOpponentStats(maskedNicknameResolver.apply(a.opponentId()),
                a.matchCount(), a.wins(), a.losses(), a.winRate(), a.badge());
        List<RivalOpponentStats> rivals = aggregate.rivals().stream().map(toStats).toList();
        return new RivalryStats(rivals.size(),
                toStats.apply(aggregate.nemesis()), toStats.apply(aggregate.dominant()), rivals);
    }
}