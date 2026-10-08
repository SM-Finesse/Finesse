package com.finesse.backend.calc.domain;

import java.util.List;

/**
 * RivalryCalculator 결과 — 모듈 내부 전용 (설계서 11.12절).
 * Facade가 PseudonymScope로 마스킹 닉네임을 붙여 RivalryStats로 변환한다.
 */
public record RivalryAggregate(
        List<RivalOpponentAggregate> rivals,
        RivalOpponentAggregate nemesis,
        RivalOpponentAggregate dominant
) {
    public RivalryAggregate {
        rivals = List.copyOf(rivals);
    }

    public static RivalryAggregate empty() {
        return new RivalryAggregate(List.of(), null, null);
    }
}