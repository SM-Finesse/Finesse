package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchSeriesStats;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import com.finesse.backend.calc.domain.RivalryStats;
import com.finesse.backend.calc.domain.StatResult;
import com.finesse.backend.calc.service.AnalysisMeta;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.StatsLoadProperties;
import com.finesse.backend.dto.StatsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * calc 결과(AnalysisOutcome) → /stats 응답 매핑 — 데이터 엔지니어링 2차 반영(10/6:
 * 콜드스타트 승패, UserSummary apm·pps·vs, TR 시계열·라운드 곡선).
 */
class StatsServiceMappingTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static final UserSummary SUMMARY =
            new UserSummary("abc", "s", 21000, 2300, 60, 80.0, 500, 95.5, 2.31, 210.4);

    private StatsResponse stats(AnalysisOutcome outcome) {
        StatCalculatorFacade facade = mock(StatCalculatorFacade.class);
        when(facade.analyze("abc")).thenReturn(outcome);
        TetrioClient tetrio = mock(TetrioClient.class);
        when(tetrio.fetchUserInfo(any(), any())).thenReturn(TetrioClient.UserInfo.empty());
        CaffeineCacheManager caches = new CaffeineCacheManager(
                CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE);
        return new StatsService(facade, tetrio, caches, new EndpointProperties(20, 40, 60, 120),
                new StatsLoadProperties(2, 5), executor).getStats("abc", false);
    }

    private static RecentWinLossStats winLoss(MatchResult... newestFirst) {
        long wins = List.of(newestFirst).stream().filter(m -> m == WIN).count();
        double rate = (double) wins / newestFirst.length;
        return new RecentWinLossStats(newestFirst.length, (int) wins, newestFirst.length - (int) wins, rate,
                newestFirst.length, rate, 0.0, List.of(newestFirst));
    }

    @Test
    void 콜드스타트도_있는_경기만큼_승률과_승패를_채운다() {
        StatsResponse r = stats(new AnalysisOutcome.ColdStartBypass(SUMMARY, 5,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_IN_YEAR, winLoss(WIN, LOSE, WIN, WIN, LOSE)));

        assertThat(r.coldStart()).isTrue();
        assertThat(r.fixedMetrics().winRate()).isEqualTo(0.6);
        assertThat(r.fixedMetrics().recentForm()).containsExactly("W", "L", "W", "W", "L");
        assertThat(r.matchCount()).isEqualTo(5);
    }

    @Test
    void 콜드스타트_경기_수는_승패를_계산한_유효_경기_수로_맞춘다() {
        // 누적 7판이지만 1년 안 유효 경기는 3판(2승 1패) — 7로 두면 프론트가 승·패를 5승 2패로 계산한다
        StatsResponse r = stats(new AnalysisOutcome.ColdStartBypass(SUMMARY, 7,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_TOTAL, winLoss(WIN, LOSE, WIN)));

        assertThat(r.matchCount()).isEqualTo(3);
        assertThat(Math.round(r.fixedMetrics().winRate() * r.matchCount())).isEqualTo(2);
    }

    @Test
    void 콜드스타트_0판이면_승률은_생략() {
        StatsResponse r = stats(new AnalysisOutcome.ColdStartBypass(SUMMARY, 0,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_TOTAL, null));

        assertThat(r.fixedMetrics().winRate()).isNull();
        assertThat(r.fixedMetrics().recentForm()).isEmpty();
    }

    @Test
    void 프로필에_apm_pps_vs를_채운다() {
        StatsResponse r = stats(new AnalysisOutcome.ColdStartBypass(SUMMARY, 0,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_TOTAL, null));

        assertThat(r.profile().apm()).isEqualTo(95.5);
        assertThat(r.profile().pps()).isEqualTo(2.31);
        assertThat(r.profile().vs()).isEqualTo(210.4);
    }

    @Test
    void 분석_결과의_TR_시계열과_라운드_곡선을_옮기고_delta가_없어도_실패하지_않는다() {
        MatchSeriesStats series = new MatchSeriesStats(
                List.of(new MatchSeriesStats.TrPoint(Instant.parse("2026-10-01T00:00:00Z"), 20900.0),
                        new MatchSeriesStats.TrPoint(Instant.parse("2026-10-02T00:00:00Z"), 21010.5)),
                List.of(new MatchSeriesStats.RoundPoint(1, 2.4, 2.1, 30),
                        new MatchSeriesStats.RoundPoint(2, 2.38, 2.0, 25)));
        List<HighlightStats.StrengthQuintile> quintiles = List.of(
                new HighlightStats.StrengthQuintile(1, 6, 5, 5 / 6.0),
                new HighlightStats.StrengthQuintile(2, 6, 4, 4 / 6.0),
                new HighlightStats.StrengthQuintile(3, 6, 3, 0.5),
                new HighlightStats.StrengthQuintile(4, 6, 3, 0.5),
                new HighlightStats.StrengthQuintile(5, 6, 2, 2 / 6.0));
        HighlightStats highlight = new HighlightStats(12.0, -0.2, 10, 4, 0.4, 8, 2, 0.25, 0.15, 0.5, true, quintiles);
        StatResult result = new StatResult(null, null, highlight, winLoss(WIN, WIN, LOSE), null,
                RivalryStats.empty(), series);

        StatsResponse r = stats(new AnalysisOutcome.Analyzed(SUMMARY, result,
                new AnalysisMeta(30, 0, false, 0, 0, 2, 1)));

        assertThat(r.fixedMetrics().trTrend()).containsExactly(20900.0, 21010.5);
        assertThat(r.chapters()).containsEntry("ended_early_matches", 2).containsEntry("format_unknown_matches", 1)
                .doesNotContainKey("previous_matches");
        assertThat(r.roundCurves().pps()).containsExactly(2.4, 2.38);
        assertThat(r.roundCurves().vs()).containsExactly(2.1, 2.0);
        assertThat(r.roundCurves().samples()).containsExactly(30, 25);
        // 분위별 승률은 Q1 → Q5 순서 그대로
        assertThat(r.deltaMetrics().strengthQuintiles()).extracting(StatsResponse.StrengthQuintile::quintile)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(r.deltaMetrics().strengthQuintiles().get(4).wins()).isEqualTo(2);
        assertThat(r.deltaMetrics().strengthQuintiles().get(4).matches()).isEqualTo(6);
        assertThat(r.deltaMetrics().attack()).isNull(); // 계산 가능한 매치가 없어 delta가 null
        assertThat(r.deltaMetrics().comebackRate()).isEqualTo(0.4);
        assertThat(r.deltaMetrics().deltaComeback()).isEqualTo(0.15); // calc 값 그대로 — 프론트 하이라이트 근거 값
        assertThat(r.deltaMetrics().comebackSamples().comebackOpportunities()).isEqualTo(10);
        // TR 있는 경기 2판 — N은 하한 3이지만 판수를 넘을 수 없어 2
        StatsResponse.TrTrendBasis basis = r.deltaMetrics().trTrendBasis();
        assertThat(basis.recentMatches()).isEqualTo(2);
        assertThat(basis.totalMatches()).isEqualTo(2);
        assertThat(basis.overallAvgTr()).isEqualTo(20955.25);
        assertThat(basis.recentAvgTr()).isEqualTo(20967.25); // 전체 평균 + tr_trend_delta(12.0)
    }

    @Test
    void TR_추이_N은_calc와_같은_식() {
        assertThat(StatsService.trTrendN(10, 0.3)).isEqualTo(3);   // ceil(3.0)
        assertThat(StatsService.trTrendN(9, 0.3)).isEqualTo(3);    // ceil(2.7)
        assertThat(StatsService.trTrendN(16, 0.3)).isEqualTo(5);   // ceil(4.8)
        assertThat(StatsService.trTrendN(100, 0.3)).isEqualTo(30); // 상한
        assertThat(StatsService.trTrendN(300, 0.3)).isEqualTo(30);
        assertThat(StatsService.trTrendN(2, 0.3)).isEqualTo(2);    // 판수보다 클 수 없음
        assertThat(StatsService.trTrendN(40, 0.5)).isEqualTo(20);  // 비율 설정을 따름
    }

    @Test
    void TR_추이_근거_값은_최근_N판_평균과_전체_평균() {
        // 16판(오래된 → 최근): N = 5 → 최근 5판 평균 1130, 전체 평균 1075, 차이 55 (calc 테스트와 같은 예)
        List<Double> tr = List.of(1000.0, 1010.0, 1020.0, 1030.0, 1040.0, 1050.0, 1060.0, 1070.0,
                1080.0, 1090.0, 1100.0, 1110.0, 1120.0, 1130.0, 1140.0, 1150.0);
        double overall = tr.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double recent = tr.subList(11, 16).stream().mapToDouble(Double::doubleValue).average().orElseThrow();

        StatsResponse.TrTrendBasis basis = StatsService.trTrendBasis(tr, recent - overall, 0.3);

        assertThat(basis.recentMatches()).isEqualTo(5);
        assertThat(basis.totalMatches()).isEqualTo(16);
        assertThat(basis.overallAvgTr()).isEqualTo(1075.0);
        assertThat(basis.recentAvgTr()).isCloseTo(1130.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 프로필_배지_5칸은_calc_변화율_그대로_N과_함께() {
        StatResult result = new StatResult(null, null,
                new HighlightStats(null, null, 0, 0, null, 0, 0, null, null, 0.0, false, List.of()),
                winLoss(WIN, LOSE, WIN), new ProfileWindowDeltaStats(9.4, -3.2, 1.5, null, 0.0),
                RivalryStats.empty(), null);

        StatsResponse r = stats(new AnalysisOutcome.Analyzed(SUMMARY, result,
                new AnalysisMeta(86, 0, false, 0, 0, 0, 0)));

        StatsResponse.WindowDelta d = r.profile().windowDelta();
        assertThat(d.recentMatches()).isEqualTo(26); // ceil(86 × 0.3)
        assertThat(d.trDeltaPct()).isEqualTo(9.4);
        assertThat(d.wrDeltaPct()).isEqualTo(-3.2);
        assertThat(d.apmDeltaPct()).isEqualTo(1.5);
        assertThat(d.ppsDeltaPct()).isNull();
        assertThat(d.vsDeltaPct()).isEqualTo(0.0);
    }

    @Test
    void 비교할_나머지_판이_없거나_콜드스타트면_배지는_생략() {
        assertThat(StatsService.windowDelta(ProfileWindowDeltaStats.unavailable(), 3, 0.3)).isNull();
        assertThat(StatsService.windowDelta(null, 30, 0.3)).isNull();

        StatsResponse cold = stats(new AnalysisOutcome.ColdStartBypass(SUMMARY, 5,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_IN_YEAR, winLoss(WIN, LOSE, WIN, WIN, LOSE)));
        assertThat(cold.profile().windowDelta()).isNull();
    }

    @Test
    void 공격_수비에_본인_상대_평균을_함께_넣는다() {
        DeltaStats.StatAverages mine = new DeltaStats.StatAverages(100, 2.5, 220, 0.667, 1.20, 2.20, 30.0);
        DeltaStats.StatAverages opp = new DeltaStats.StatAverages(95, 2.4, 230, 0.660, 1.25, 2.42, 28.5);
        DeltaStats delta = new DeltaStats(40, 0.1, 5, -10, 0.007, -0.05, -0.22, 1.5,
                null, null, null, null, mine, opp);

        StatsResponse.Attack attack = StatsService.attack(delta);
        StatsResponse.Defense defense = StatsService.defense(delta);

        assertThat(attack.deltaApp()).isEqualTo(0.007); // 기존 Δ 키는 그대로
        assertThat(attack.myAvg()).isEqualTo(new StatsResponse.AttackAvg(0.667, 1.20));
        assertThat(attack.oppAvg()).isEqualTo(new StatsResponse.AttackAvg(0.660, 1.25));
        assertThat(defense.deltaCheeseIndex()).isEqualTo(1.5);
        assertThat(defense.myAvg()).isEqualTo(new StatsResponse.DefenseAvg(2.20, 30.0));
        assertThat(defense.oppAvg()).isEqualTo(new StatsResponse.DefenseAvg(2.42, 28.5));
    }

    @Test
    void 분위가_없으면_strength_quintiles는_생략() {
        HighlightStats highlight = new HighlightStats(null, null, 0, 0, null, 0, 0, null, null, 0.0, false, List.of());
        assertThat(StatsService.strengthQuintiles(highlight)).isNull();
    }

    @Test
    void TR_있는_경기가_없으면_근거_값은_생략() {
        assertThat(StatsService.trTrendBasis(List.of(), null, 0.3)).isNull();
        assertThat(StatsService.trTrendBasis(List.of(21000.0), null, 0.3)).isNull();
    }

    @Test
    void PPS가_없는_라운드가_있으면_pps는_빈_배열() {
        MatchSeriesStats series = new MatchSeriesStats(List.of(),
                List.of(new MatchSeriesStats.RoundPoint(1, 2.4, 2.1, 30),
                        new MatchSeriesStats.RoundPoint(2, null, 2.0, 1)));

        StatsResponse.RoundCurves curves = StatsService.roundCurves(series);

        assertThat(curves.pps()).isEmpty();
        assertThat(curves.vs()).containsExactly(2.1, 2.0);
    }
}
