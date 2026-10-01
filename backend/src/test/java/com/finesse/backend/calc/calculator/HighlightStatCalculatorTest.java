package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.calc.fixture.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HighlightStatCalculatorTest {

    private static final double TOL = 1e-9;
    private final HighlightStatCalculator calculator = new HighlightStatCalculator();

    private HighlightStats calc(List<MatchHistory> matches) {
        return calculator.calculate(new AnalyticsContext(matches, null));
    }

    // ── TR Trend Delta (11.1) ─────────────────────────────

    @Test
    void TR_Trend는_10판이면_최근_1판_평균에서_전체_평균을_뺀다() {
        // 매치 당시 TR 1000, 1010, ..., 1090 (나중 판일수록 높음)
        // N = ceil(10 × 0.1) = 1 → 최근 1판 1090 − 전체 평균 1045 = 45
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 10; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        assertThat(calc(matches).trTrendDelta()).isCloseTo(45.0, within(TOL));
    }

    @Test
    void TR_Trend의_N은_소수점이면_올림한다() {
        // 11판: N = ceil(1.1) = 2 → 최근 2판 평균 1095 − 전체 평균 1050 = 45
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s <= 10; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        assertThat(calc(matches).trTrendDelta()).isCloseTo(45.0, within(TOL));
    }

    // ── strength_split (11.2, 11.6, 11.7) ─────────────────

    @Test
    void strength_split은_강한_상대_Q1_승률에서_약한_상대_Q5_승률을_빼며_보통_음수다() {
        // 본인 TR 1000, 상대 TR 1100, 1090, ..., 1010 → TR Gap(상대 − 본인) = 100 ... 10
        // Q1(Gap 100, 90 = 강한 상대) = L, L → 0.0
        // Q5(Gap 20, 10 = 약한 상대)  = W, L → 0.5
        // strength_split = 0.0 − 0.5 = −0.5
        MatchResult[] results = {LOSE, LOSE, LOSE, WIN, WIN, LOSE, WIN, WIN, WIN, LOSE};
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 10; i++) matches.add(tr(i, 1000, 1000 + (100 - 10 * i), results[i]));

        assertThat(calc(matches).strengthSplit()).isCloseTo(-0.5, within(TOL));
    }

    @Test
    void TR_Gap이_같으면_최신_매치를_앞에_두어_입력_순서와_무관하게_같은_결과를_낸다() {
        // Gap이 전부 0 → 최신순 정렬. 최신 2판(초 9, 8)만 WIN → Q1 1.0 − Q5 0.0 = 1.0
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 10; s++) matches.add(tr(s, 1000, 1000, s >= 8 ? WIN : LOSE));
        List<MatchHistory> reversed = new ArrayList<>(matches);
        Collections.reverse(reversed);

        assertThat(calc(matches).strengthSplit()).isCloseTo(1.0, within(TOL));
        assertThat(calc(reversed)).isEqualTo(calc(matches));
    }

    @Test
    void 분위_나머지는_1분위부터_1개씩_배분한다() {
        // 53판 → 몫 10, 나머지 3 → 11, 11, 11, 10, 10 (11.7절)
        var bounds = HighlightStatCalculator.computeQuintileBounds(53);

        assertThat(bounds).extracting(HighlightStatCalculator.QuintileBounds::size)
                .containsExactly(11, 11, 11, 10, 10);
        assertThat(bounds).extracting(HighlightStatCalculator.QuintileBounds::startOffset)
                .containsExactly(0, 11, 22, 33, 43);
    }

    // ── comeback_rate (11.3) ──────────────────────────────

    @Test
    void 두_판_이상_뒤진_적이_있는_매치만_역전_기회로_센다() {
        // A: L L W W W (WIN)  → 3라운드 시작 전 0:2 → 기회, 성공
        // B: L L W L   (LOSE) → 3라운드 시작 전 0:2 → 기회, 실패
        // C: W L W     (WIN)  → 최대 1점 차 → 기회 아님
        HighlightStats s = calc(List.of(
                rounds(WIN, false, false, true, true, true),
                rounds(LOSE, false, false, true, false),
                rounds(WIN, true, false, true)));

        assertThat(s.comebackOpportunities()).isEqualTo(2);
        assertThat(s.comebackWon()).isEqualTo(1);
        assertThat(s.comebackRate()).isCloseTo(0.5, within(TOL));
    }

    @Test
    void 역전_기회가_0회면_comeback_rate는_null이다() {
        HighlightStats s = calc(List.of(rounds(WIN, true, false, true)));

        assertThat(s.comebackOpportunities()).isZero();
        assertThat(s.comebackRate()).isNull();
    }

    // ── comeback_rate_against (11.3부) ────────────────────

    @Test
    void 역전_허용_기회가_있으면_역전당한_비율을_계산한다() {
        // W W L L L (LOSE) × 3 → 역전당함 / W W W (WIN) × 7 → 지켜냄
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 3; i++) matches.add(rounds(LOSE, true, true, false, false, false));
        for (int i = 0; i < 7; i++) matches.add(rounds(WIN, true, true, true));

        HighlightStats s = calc(matches);

        assertThat(s.comebackAgainstOpportunities()).isEqualTo(10);
        assertThat(s.comebackAgainstAllowed()).isEqualTo(3);
        assertThat(s.comebackRateAgainst()).isCloseTo(0.3, within(TOL));
    }

    @Test
    void 역전_허용_기회가_10회_미만이어도_비율을_계산한다() {
        // 최소 표본 10회 조건 폐기 (하이라이트 지표 설계 2026-09-30: 분모 0일 때만 null)
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 9; i++) matches.add(rounds(WIN, true, true, true));

        HighlightStats s = calc(matches);

        assertThat(s.comebackAgainstOpportunities()).isEqualTo(9);
        assertThat(s.comebackAgainstAllowed()).isZero();
        assertThat(s.comebackRateAgainst()).isCloseTo(0.0, within(TOL));
    }

    @Test
    void 역전_허용_기회가_0회면_comeback_rate_against는_null이다() {
        HighlightStats s = calc(List.of(rounds(WIN, true, false, true)));

        assertThat(s.comebackAgainstOpportunities()).isZero();
        assertThat(s.comebackRateAgainst()).isNull();
    }

    // ── delta_comeback (하이라이트 지표 설계 2026-09-30) ──

    @Test
    void delta_comeback은_comeback_rate에서_comeback_rate_against를_뺀다() {
        // 역전 기회: L L W W W (WIN, 성공), L L W L (LOSE, 실패)      → comeback_rate 1/2
        // 역전 허용 기회: W W L L L (LOSE, 역전당함), W W W (WIN) × 2 → comeback_rate_against 1/3
        // delta_comeback = 0.5 − 0.3333 = 0.1667
        HighlightStats s = calc(List.of(
                rounds(WIN, false, false, true, true, true),
                rounds(LOSE, false, false, true, false),
                rounds(LOSE, true, true, false, false, false),
                rounds(WIN, true, true, true),
                rounds(WIN, true, true, true)));

        assertThat(s.comebackRate()).isCloseTo(0.5, within(TOL));
        assertThat(s.comebackRateAgainst()).isCloseTo(1.0 / 3, within(TOL));
        assertThat(s.deltaComeback()).isCloseTo(0.5 - 1.0 / 3, within(TOL));
    }

    @Test
    void 두_비율_중_하나라도_분모가_0이면_delta_comeback은_null이다() {
        HighlightStats onlyComeback = calc(List.of(rounds(WIN, false, false, true, true, true)));
        HighlightStats onlyLead = calc(List.of(rounds(WIN, true, true, true)));

        assertThat(onlyComeback.comebackRate()).isNotNull();
        assertThat(onlyComeback.deltaComeback()).isNull();
        assertThat(onlyLead.comebackRateAgainst()).isNotNull();
        assertThat(onlyLead.deltaComeback()).isNull();
    }

    // ── session_vs_slope (11.4, 11.5) ─────────────────────

    @Test
    void 라운드별_평균_VS에_대한_OLS_기울기를_계산한다() {
        // 라운드 0: (10+30)/2=20, 라운드 1: (20+40)/2=30, 라운드 2: (30+50)/2=40 → 기울기 10
        HighlightStats s = calc(List.of(vsRounds(10, 20, 30), vsRounds(30, 40, 50)));

        assertThat(s.sessionVsSlope()).isCloseTo(10.0, within(TOL));
    }

    @Test
    void 서로_다른_라운드_인덱스가_1개뿐이면_기울기는_0이다() {
        HighlightStats s = calc(List.of(vsRounds(10), vsRounds(50)));

        assertThat(s.sessionVsSlope()).isEqualTo(0.0);
    }

    @Test
    void 평균_VS가_모두_같으면_정상_계산_결과로_0이다() {
        // 라운드 0: (10+30)/2=20, 라운드 1: 20 → 기울기 0
        HighlightStats s = calc(List.of(vsRounds(10, 20), vsRounds(30)));

        assertThat(s.sessionVsSlope()).isCloseTo(0.0, within(TOL));
    }

    // ── eligible ──────────────────────────────────────────

    @Test
    void 매치가_없으면_모든_지표가_null이고_eligible은_false다() {
        HighlightStats s = calc(List.of());

        assertThat(s.trTrendDelta()).isNull();
        assertThat(s.strengthSplit()).isNull();
        assertThat(s.comebackRate()).isNull();
        assertThat(s.comebackRateAgainst()).isNull();
        assertThat(s.deltaComeback()).isNull();
        assertThat(s.sessionVsSlope()).isEqualTo(0.0);
        assertThat(s.eligible()).isFalse();
    }

    @Test
    void 매치_당시_TR이_없는_매치는_TR_지표에서만_제외한다() {
        // TR 없는 5판(LOSE) + TR 1050~1090 5판(WIN)
        // TR Trend: TR 있는 5판 기준 N = 1 → 1090 − 1070 = 20
        // strength_split: TR 있는 5판이 모두 WIN → 0.0 (TR 없는 LOSE는 반영되지 않음)
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 5; s++) matches.add(withoutTr(s, LOSE));
        for (int s = 5; s < 10; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        HighlightStats h = calc(matches);

        assertThat(h.trTrendDelta()).isCloseTo(20.0, within(TOL));
        assertThat(h.strengthSplit()).isCloseTo(0.0, within(TOL));
    }
}