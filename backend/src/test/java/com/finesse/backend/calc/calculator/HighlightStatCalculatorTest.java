package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.config.AnalyticsProperties;
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
    void TR_Trend는_10판이면_N이_3이다() {
        // 매치 당시 TR 1000, 1010, ..., 1090 (나중 판일수록 높음)
        // N = clamp(ceil(10 × 0.3) = 3, 3, 30) = 3 → 최근 3판 평균 1080 − 전체 평균 1045 = 35
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 10; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        assertThat(calc(matches).trTrendDelta()).isCloseTo(35.0, within(TOL));
    }

    @Test
    void TR_Trend의_N은_소수점이면_올림한다() {
        // 16판: N = ceil(16 × 0.3) = ceil(4.8) = 5 → 최근 5판 평균 1130 − 전체 평균 1075 = 55
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 16; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        assertThat(calc(matches).trTrendDelta()).isCloseTo(55.0, within(TOL));
    }

    @Test
    void TR_Trend의_N은_3에서_30_사이이고_TR_있는_판수를_넘지_않는다() {
        assertThat(HighlightStatCalculator.trTrendN(10, 0.3)).isEqualTo(3);   // 기본값 0.3
        assertThat(HighlightStatCalculator.trTrendN(9, 0.3)).isEqualTo(3);    // ceil(2.7)
        assertThat(HighlightStatCalculator.trTrendN(100, 0.3)).isEqualTo(30); // 상한
        assertThat(HighlightStatCalculator.trTrendN(10, 0.2)).isEqualTo(3);   // ceil(2.0) → 하한 3
        assertThat(HighlightStatCalculator.trTrendN(20, 0.2)).isEqualTo(4);
        assertThat(HighlightStatCalculator.trTrendN(150, 0.2)).isEqualTo(30);
        assertThat(HighlightStatCalculator.trTrendN(300, 0.2)).isEqualTo(30); // ceil(60) → 상한 30
        assertThat(HighlightStatCalculator.trTrendN(300, 0.1)).isEqualTo(30);
        assertThat(HighlightStatCalculator.trTrendN(40, 0.5)).isEqualTo(20);
        assertThat(HighlightStatCalculator.trTrendN(2, 0.2)).isEqualTo(2);   // TR 있는 판이 2판뿐
    }

    @Test
    void TR_Trend_비율은_설정값을_따른다() {
        // 40판, 비율 0.5 → N = 20 → 최근 20판 평균 1295 − 전체 평균 1195 = 100
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 40; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));
        var halfRatio = new HighlightStatCalculator(new AnalyticsProperties(10, 300, 40, 0.5));

        assertThat(halfRatio.calculate(new AnalyticsContext(matches, null)).trTrendDelta())
                .isCloseTo(100.0, within(TOL));
    }

    // ── strength_split (11.2, 11.6, 11.7) ─────────────────

    @Test
    void strength_split은_강한_상대_Q5_승률에서_약한_상대_Q1_승률을_빼며_보통_음수다() {
        // 본인 TR 1000, 상대 TR 1100, 1090, ..., 1010 → TR Gap(본인 − 상대) = −100 ... −10
        // 내림차순: Q1(Gap −10, −20 = 약한 상대) = L, W → 0.5
        //          Q5(Gap −90, −100 = 강한 상대) = L, L → 0.0
        // strength_split = Q5 − Q1 = 0.0 − 0.5 = −0.5
        MatchResult[] results = {LOSE, LOSE, LOSE, WIN, WIN, LOSE, WIN, WIN, WIN, LOSE};
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 10; i++) matches.add(tr(i, 1000, 1000 + (100 - 10 * i), results[i]));

        assertThat(calc(matches).strengthSplit()).isCloseTo(-0.5, within(TOL));
    }

    @Test
    void TR_Gap이_같으면_최신_매치를_앞에_두어_입력_순서와_무관하게_같은_결과를_낸다() {
        // Gap이 전부 0 → 최신순 정렬. 최신 2판(초 9, 8)만 WIN → Q1 1.0, Q5 0.0 → Q5 − Q1 = −1.0
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 10; s++) matches.add(tr(s, 1000, 1000, s >= 8 ? WIN : LOSE));
        List<MatchHistory> reversed = new ArrayList<>(matches);
        Collections.reverse(reversed);

        assertThat(calc(matches).strengthSplit()).isCloseTo(-1.0, within(TOL));
        assertThat(calc(reversed)).isEqualTo(calc(matches));
    }

    @Test
    void 분위별_판수_승수_승률을_약한_상대부터_반환하고_양끝_차이가_strength_split이다() {
        // 앞 테스트와 같은 10판: Q1(약한 상대) = L, W / Q5(강한 상대) = L, L
        MatchResult[] results = {LOSE, LOSE, LOSE, WIN, WIN, LOSE, WIN, WIN, WIN, LOSE};
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 10; i++) matches.add(tr(i, 1000, 1000 + (100 - 10 * i), results[i]));

        HighlightStats s = calc(matches);

        assertThat(s.strengthQuintiles()).extracting(HighlightStats.StrengthQuintile::quintile)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(s.strengthQuintiles()).extracting(HighlightStats.StrengthQuintile::matches)
                .containsExactly(2, 2, 2, 2, 2);
        assertThat(s.strengthQuintiles()).extracting(HighlightStats.StrengthQuintile::wins)
                .containsExactly(1, 2, 1, 1, 0);
        assertThat(s.strengthQuintiles().get(0).winRate()).isCloseTo(0.5, within(TOL));
        double q5MinusQ1 = s.strengthQuintiles().get(4).winRate() - s.strengthQuintiles().get(0).winRate();
        assertThat(q5MinusQ1).isCloseTo(s.strengthSplit(), within(TOL));
    }

    @Test
    void TR_있는_매치가_5판_미만이면_분위별_승률은_빈_목록이다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 4; i++) matches.add(tr(i, 1000, 1100, WIN));

        assertThat(calc(matches).strengthQuintiles()).isEmpty();
        assertThat(calc(matches).strengthSplit()).isNull();
    }

    @Test
    void 분위_나머지는_강한_상대_Q5부터_1개씩_배분한다() {
        // 53판 → 몫 10, 나머지 3 → Q1·Q2 10판, Q3~Q5 11판 (11.7절)
        var bounds = HighlightStatCalculator.computeQuintileBounds(53);

        assertThat(bounds).extracting(HighlightStatCalculator.QuintileBounds::size)
                .containsExactly(10, 10, 11, 11, 11);
        assertThat(bounds).extracting(HighlightStatCalculator.QuintileBounds::startOffset)
                .containsExactly(0, 10, 20, 31, 42);
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

    @Test
    void 역전_기회_점수_차는_3선승_2_5선승_3_7선승_4다() {
        assertThat(HighlightStatCalculator.comebackGap(3)).isEqualTo(2);
        assertThat(HighlightStatCalculator.comebackGap(5)).isEqualTo(3);
        assertThat(HighlightStatCalculator.comebackGap(7)).isEqualTo(4);
    }

    @Test
    void 칠선승_매치는_4판_이상_뒤져야_역전_기회다() {
        // A: 0:2 → 7:2 ... 7선승 기준 점수 차 4 미만 → 기회 아님
        // B: 0:4 이후 역전승 → 기회, 성공
        HighlightStats s = calc(List.of(
                roundsWithFormat(7, false, WIN, false, false, true, true, true, true, true, true, true),
                roundsWithFormat(7, false, WIN, false, false, false, false,
                        true, true, true, true, true, true, true)));

        assertThat(s.comebackOpportunities()).isEqualTo(1);
        assertThat(s.comebackWon()).isEqualTo(1);
    }

    @Test
    void 역전_허용_기회도_경기_형식별_점수_차를_쓴다() {
        // v3.9 대칭화: 7선승은 4점 이상 앞서야 역전 허용 기회
        // A: 2:0으로 앞섰다가 짐 → 7선승 기준 4점 미만 → 기회 아님
        // B: 4:0으로 앞섰다가 짐 → 기회, 역전당함
        // C: 3선승 2:0으로 앞섰다가 짐 → 3선승은 2점 → 기회, 역전당함
        HighlightStats s = calc(List.of(
                roundsWithFormat(7, false, LOSE, true, true, false, false, false, false, false, false, false),
                roundsWithFormat(7, false, LOSE, true, true, true, true,
                        false, false, false, false, false, false, false),
                roundsWithFormat(3, false, LOSE, true, true, false, false, false)));

        assertThat(s.comebackAgainstOpportunities()).isEqualTo(2);
        assertThat(s.comebackAgainstAllowed()).isEqualTo(2);
    }

    @Test
    void 조기_종료나_형식_불명_매치는_역전_지표에서_제외한다() {
        // 0:4로 지다가 상대 이탈로 5:4 승(7선승 조기 종료) → 역전 기회로 세지 않음
        // 형식 불명 매치의 0:2 → 3:2 역전승도 세지 않음
        HighlightStats s = calc(List.of(
                roundsWithFormat(7, true, WIN, false, false, false, false, true, true, true, true, true),
                roundsWithFormat(null, false, WIN, false, false, true, true, true),
                roundsWithFormat(3, false, LOSE, true, true, false, false, false)));

        assertThat(s.comebackOpportunities()).isZero();
        assertThat(s.comebackRate()).isNull();
        assertThat(s.comebackAgainstOpportunities()).isEqualTo(1);   // 정상 매치 1판만
        assertThat(s.deltaComeback()).isNull();
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
    void 조기_종료_매치는_마지막_라운드를_빼고_기울기를_계산한다() {
        // 조기 종료 매치의 라운드 3(VS 0, 이탈로 끊긴 값)은 빠진다 → 라운드 0~2 평균 20, 30, 40 → 기울기 10
        HighlightStats s = calc(List.of(vsRounds(10, 20, 30), vsRoundsEndedEarly(30, 40, 50, 0)));

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
        // TR Trend: TR 있는 5판 기준 N = clamp(ceil(1.5), 3, 30) = 3 → 최근 3판 1080 − 1070 = 10
        // strength_split: TR 있는 5판이 모두 WIN → 0.0 (TR 없는 LOSE는 반영되지 않음)
        List<MatchHistory> matches = new ArrayList<>();
        for (int s = 0; s < 5; s++) matches.add(withoutTr(s, LOSE));
        for (int s = 5; s < 10; s++) matches.add(tr(s, 1000 + 10 * s, 1000, WIN));

        HighlightStats h = calc(matches);

        assertThat(h.trTrendDelta()).isCloseTo(10.0, within(TOL));
        assertThat(h.strengthSplit()).isCloseTo(0.0, within(TOL));
    }
}