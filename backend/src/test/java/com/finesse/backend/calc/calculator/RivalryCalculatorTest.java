package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.against;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RivalryCalculatorTest {

    private final RivalryCalculator calculator = new RivalryCalculator();

    /** opponentSeq 상대와 wins승 losses패 */
    private static List<MatchHistory> vs(int opponentSeq, int wins, int losses) {
        List<MatchHistory> list = new ArrayList<>();
        for (int i = 0; i < wins; i++) list.add(against(opponentSeq, WIN));
        for (int i = 0; i < losses; i++) list.add(against(opponentSeq, LOSE));
        return list;
    }

    private RivalryAggregate calc(List<MatchHistory> matches) {
        return calculator.calculate(new AnalyticsContext(matches));
    }

    private List<MatchHistory> sample() {
        List<MatchHistory> m = new ArrayList<>();
        m.addAll(vs(0, 1, 5)); // User_A: 6판 1승 16.7% → 천적
        m.addAll(vs(1, 4, 1)); // User_B: 5판 4승 80%   → 우세
        m.addAll(vs(2, 3, 3)); // User_C: 6판 50%       → 접전
        m.addAll(vs(3, 3, 1)); // User_D: 4판           → 5경기 미만, 제외
        m.addAll(vs(4, 3, 2)); // User_E: 5판 60%       → 배지 없음
        return m;
    }

    @Test
    void 반복_5경기_이상_만난_상대만_집계하고_만난_횟수_내림차순으로_정렬한다() {
        RivalryAggregate r = calc(sample());

        assertThat(r.rivals()).extracting(a -> a.opponentId().value())
                .containsExactly("User_A", "User_C", "User_B", "User_E");
        assertThat(r.rivals()).extracting(RivalOpponentAggregate::badge)
                .containsExactly(RivalBadge.NEMESIS, RivalBadge.CLOSE, RivalBadge.DOMINANT, RivalBadge.NONE);
        assertThat(r.rivals().get(0).winRate()).isCloseTo(16.6667, within(1e-4));
    }

    @Test
    void 천적은_40퍼센트_이하_중_최저_우세는_65퍼센트_이상_중_최고_승률_상대다() {
        RivalryAggregate r = calc(sample());

        assertThat(r.nemesis().opponentId().value()).isEqualTo("User_A");
        assertThat(r.dominant().opponentId().value()).isEqualTo("User_B");
    }

    @Test
    void 배지_경계값() {
        assertThat(RivalryCalculator.classify(40.0)).isEqualTo(RivalBadge.NEMESIS);
        assertThat(RivalryCalculator.classify(40.1)).isEqualTo(RivalBadge.NONE);
        assertThat(RivalryCalculator.classify(45.0)).isEqualTo(RivalBadge.CLOSE);
        assertThat(RivalryCalculator.classify(55.0)).isEqualTo(RivalBadge.CLOSE);
        assertThat(RivalryCalculator.classify(55.1)).isEqualTo(RivalBadge.NONE);
        assertThat(RivalryCalculator.classify(64.9)).isEqualTo(RivalBadge.NONE);
        assertThat(RivalryCalculator.classify(65.0)).isEqualTo(RivalBadge.DOMINANT);
    }

    @Test
    void 반복_조우_상대가_없으면_빈_결과다() {
        assertThat(calc(vs(0, 2, 2))).isEqualTo(RivalryAggregate.empty());
    }

    @Test
    void 입력_순서와_무관하게_같은_결과를_낸다() {
        List<MatchHistory> reversed = new ArrayList<>(sample());
        Collections.reverse(reversed);

        assertThat(calc(reversed)).isEqualTo(calc(sample()));
    }

    @Test
    void 외부_노출용_변환에는_마스킹_닉네임만_들어간다() {
        RivalryStats stats = RivalryStats.from(calc(sample()), id -> "masked-" + id.value());

        assertThat(stats.rivalCount()).isEqualTo(4);
        assertThat(stats.nemesis().maskedNickname()).isEqualTo("masked-User_A");
        assertThat(stats.dominant().maskedNickname()).isEqualTo("masked-User_B");
        assertThat(RivalryStats.from(RivalryAggregate.empty(), id -> "x")).isEqualTo(RivalryStats.empty());
    }
}