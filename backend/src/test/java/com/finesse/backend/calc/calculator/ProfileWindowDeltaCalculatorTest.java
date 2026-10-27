package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 최근 N판 vs 나머지 변화율 (설계서 11.11절, v3.10). */
class ProfileWindowDeltaCalculatorTest {

    private static final double TOL = 1e-9;
    private final ProfileWindowDeltaCalculator calculator = new ProfileWindowDeltaCalculator();

    /** 나머지 7판을 먼저, 최근 3판을 나중에 만든다(fixture는 만든 순서대로 시각이 늦어진다 → 나중 것이 최신). */
    private static List<MatchHistory> tenMatches() {
        List<MatchHistory> matches = new ArrayList<>();
        // 나머지 7판: APM 60 / PPS 1.0 / VS 100 / TR 1000 / 2승 5패
        for (int i = 0; i < 7; i++) matches.add(stats(60, 1.0, 100, 1000, i < 2 ? WIN : LOSE));
        // 최근 3판: APM 90 / PPS 1.2 / VS 130 / TR 1100 / 3승
        for (int i = 0; i < 3; i++) matches.add(stats(90, 1.2, 130, 1100, WIN));
        return matches;
    }

    @Test
    void 최근_N판과_나머지의_평균_변화율을_계산한다() {
        // 10판 × 0.3 = 3 → 최근 3판 vs 나머지 7판
        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(tenMatches()));

        assertThat(s.isAvailable()).isTrue();
        assertThat(s.trDeltaPct()).isCloseTo(10.0, within(TOL));    // (1100−1000)/1000×100
        assertThat(s.apmDeltaPct()).isCloseTo(50.0, within(TOL));   // (90−60)/60×100
        assertThat(s.ppsDeltaPct()).isCloseTo(20.0, within(TOL));   // (1.2−1.0)/1.0×100
        assertThat(s.vsDeltaPct()).isCloseTo(30.0, within(TOL));    // (130−100)/100×100
        double restWr = 2 * 100.0 / 7;                              // 28.57%
        assertThat(s.wrDeltaPct()).isCloseTo((100.0 - restWr) / restWr * 100, within(TOL));
    }

    @Test
    void 입력_순서와_무관하게_최신_매치를_최근_구간으로_본다() {
        List<MatchHistory> reversed = new ArrayList<>(tenMatches());
        java.util.Collections.reverse(reversed);

        assertThat(calculator.calculate(new AnalyticsContext(reversed)).apmDeltaPct())
                .isCloseTo(50.0, within(TOL));
    }

    @Test
    void N은_판수_곱하기_비율을_올림해_3에서_30_사이로_자른다() {
        assertThat(ProfileWindowDeltaCalculator.recentN(10, 0.3)).isEqualTo(3);
        assertThat(ProfileWindowDeltaCalculator.recentN(50, 0.3)).isEqualTo(15);
        assertThat(ProfileWindowDeltaCalculator.recentN(295, 0.3)).isEqualTo(30);  // ceil(88.5) → 상한 30
        assertThat(ProfileWindowDeltaCalculator.recentN(5, 0.3)).isEqualTo(3);     // ceil(1.5) → 하한 3
        assertThat(ProfileWindowDeltaCalculator.recentN(0, 0.3)).isZero();
    }

    @Test
    void 비율은_설정값을_따른다() {
        // 비율 0.5 → 10판이면 최근 5판 vs 나머지 5판
        var half = new ProfileWindowDeltaCalculator(new AnalyticsProperties(10, 300, 40, 0.5));
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 5; i++) matches.add(stats(50, 1.0, 100, 1000, LOSE));
        for (int i = 0; i < 5; i++) matches.add(stats(100, 1.0, 100, 1000, WIN));

        assertThat(half.calculate(new AnalyticsContext(matches)).apmDeltaPct()).isCloseTo(100.0, within(TOL));
    }

    @Test
    void 나머지_구간이_비면_5개_필드가_모두_null이다() {
        // 3판 → N = 3 → 나머지 0판
        List<MatchHistory> matches = List.of(
                stats(60, 1.0, 120, 1000, WIN), stats(60, 1.0, 120, 1000, WIN), stats(60, 1.0, 120, 1000, WIN));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(matches));

        assertThat(s.isAvailable()).isFalse();
        assertThat(s).isEqualTo(ProfileWindowDeltaStats.unavailable());
    }

    @Test
    void 나머지_구간_값이_0이면_변화율은_0이다() {
        // 나머지 7판 전패(승률 0%) → 분모 0 방어
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 7; i++) matches.add(stats(60, 1.0, 100, 1000, LOSE));
        for (int i = 0; i < 3; i++) matches.add(stats(60, 1.0, 100, 1000, WIN));

        assertThat(calculator.calculate(new AnalyticsContext(matches)).wrDeltaPct()).isCloseTo(0.0, within(TOL));
    }

    @Test
    void 한_구간에_매치_당시_TR이_없으면_TR_변화율만_null이다() {
        // 최근 3판은 TR 없음 (시각을 크게 둬서 가장 최신이 되게 한다)
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 7; i++) matches.add(stats(50, 1.0, 100, 1000, i % 2 == 0 ? WIN : LOSE));
        for (int i = 0; i < 3; i++) matches.add(withoutTr(1_000_000 + i, WIN));

        ProfileWindowDeltaStats s = calculator.calculate(new AnalyticsContext(matches));

        assertThat(s.isAvailable()).isTrue();
        assertThat(s.trDeltaPct()).isNull();
        assertThat(s.apmDeltaPct()).isCloseTo(20.0, within(TOL));   // withoutTr의 APM 60 vs 50
    }
}