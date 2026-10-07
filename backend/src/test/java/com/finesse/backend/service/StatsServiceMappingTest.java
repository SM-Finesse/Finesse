package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchSeriesStats;
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
        HighlightStats highlight = new HighlightStats(12.0, -0.2, 10, 4, 0.4, 8, 2, 0.25, 0.15, 0.5, true);
        StatResult result = new StatResult(null, null, highlight, winLoss(WIN, WIN, LOSE), null,
                RivalryStats.empty(), series);

        StatsResponse r = stats(new AnalysisOutcome.Analyzed(SUMMARY, result,
                new AnalysisMeta(30, 0, false, 0, 0, 2, 1)));

        assertThat(r.fixedMetrics().trTrend()).containsExactly(20900.0, 21010.5);
        assertThat(r.chapters()).containsEntry("ended_early_matches", 2).containsEntry("format_unknown_matches", 1)
                .doesNotContainKey("previous_matches");
        assertThat(r.roundCurves().pps()).containsExactly(2.4, 2.38);
        assertThat(r.roundCurves().vs()).containsExactly(2.1, 2.0);
        assertThat(r.deltaMetrics().attack()).isNull(); // 계산 가능한 매치가 없어 delta가 null
        assertThat(r.deltaMetrics().comebackRate()).isEqualTo(0.4);
        assertThat(r.deltaMetrics().deltaComeback()).isEqualTo(0.15); // calc 값 그대로 — 프론트 하이라이트 근거 값
        assertThat(r.deltaMetrics().comebackSamples().comebackOpportunities()).isEqualTo(10);
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
