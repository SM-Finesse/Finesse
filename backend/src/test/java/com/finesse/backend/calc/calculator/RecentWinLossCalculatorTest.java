package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecentWinLossCalculatorTest {

    private static final double TOL = 1e-9;

    private RecentWinLossStats calc(int window, List<MatchHistory> matches) {
        var calculator = new RecentWinLossCalculator(new AnalyticsProperties(10, 300, window));
        return calculator.calculate(new AnalyticsContext(matches, null));
    }

    @Test
    void 전체_매치가_윈도우보다_적으면_전체_매치로_계산한다() {
        // 5판(3승 2패) < 윈도우 40 → recentCount 5 (11.10절)
        RecentWinLossStats s = calc(40, List.of(
                result("m1", 1, WIN), result("m2", 2, WIN), result("m3", 3, WIN),
                result("m4", 4, LOSE), result("m5", 5, LOSE)));

        assertThat(s.recentCount()).isEqualTo(5);
        assertThat(s.wins()).isEqualTo(3);
        assertThat(s.losses()).isEqualTo(2);
        assertThat(s.winRate()).isCloseTo(0.6, within(TOL));
        assertThat(s.overallWinRate()).isCloseTo(0.6, within(TOL));
        assertThat(s.deltaVsOverall()).isCloseTo(0.0, within(TOL));
    }

    @Test
    void 입력_순서와_무관하게_최신_N판을_골라_전체_대비_증감을_계산한다() {
        // 시각 순(과거→최신): m0 L, m1 L, m2 W, m3 W, m4 L, m5 W
        // 최신 4판: m5 W, m4 L, m3 W, m2 W → 3승 1패 = 0.75
        // 전체 6판: 3승 = 0.5 → 증감 +0.25
        List<MatchHistory> shuffled = List.of(
                result("m3", 3, WIN), result("m0", 0, LOSE), result("m5", 5, WIN),
                result("m1", 1, LOSE), result("m4", 4, LOSE), result("m2", 2, WIN));

        RecentWinLossStats s = calc(4, shuffled);

        assertThat(s.recentCount()).isEqualTo(4);
        assertThat(s.wins()).isEqualTo(3);
        assertThat(s.losses()).isEqualTo(1);
        assertThat(s.winRate()).isCloseTo(0.75, within(TOL));
        assertThat(s.overallCount()).isEqualTo(6);
        assertThat(s.overallWinRate()).isCloseTo(0.5, within(TOL));
        assertThat(s.deltaVsOverall()).isCloseTo(0.25, within(TOL));
        assertThat(s.recentResults()).containsExactly(WIN, LOSE, WIN, WIN);
    }

    @Test
    void 같은_시각이면_matchId_오름차순으로_정렬한다() {
        // 같은 시각의 "b"(WIN)와 "a"(LOSE) 중 윈도우 1이면 "a"가 선택된다
        RecentWinLossStats s = calc(1, List.of(result("b", 10, WIN), result("a", 10, LOSE)));

        assertThat(s.recentResults()).containsExactly(LOSE);
    }
}