package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
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
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.finesse.backend.calc.domain.MatchResult.LOSE;
import static com.finesse.backend.calc.domain.MatchResult.WIN;
import static com.finesse.backend.trainingdata.TrainingDataCollectorTest.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수집기가 저장하는 payload가 서비스가 LLM에 보내는 요청과 같은지 — 실제 StatsService 매핑을 거친다.
 */
class TrainingPayloadBuilderTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TetrioClient tetrio = mock(TetrioClient.class);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private StatsService statsService() {
        when(tetrio.fetchUserInfo(any(), any())).thenReturn(TetrioClient.UserInfo.empty());
        return new StatsService(mock(StatCalculatorFacade.class), tetrio,
                new CaffeineCacheManager(CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE),
                new EndpointProperties(20, 40, 60, 120), new StatsLoadProperties(2, 5), executor);
    }

    private static AnalysisOutcome.Analyzed analyzed() {
        List<MatchResult> form = List.of(WIN, WIN, LOSE);
        RecentWinLossStats winLoss = new RecentWinLossStats(3, 2, 1, 2 / 3.0, 3, 2 / 3.0, 0.0, form);
        DeltaStats delta = new DeltaStats(30, 0.1, 2.0, 0.05, 0.02, 1.4, 0.31, -2.1, -0.05, 0.18, -0.09, 0.12,
                new DeltaStats.StatAverages(90, 2.4, 190, 0.62, 0.55, 2.1, -20),
                new DeltaStats.StatAverages(88, 2.3, 185, 0.60, 0.40, 1.79, -17.9));
        HighlightStats highlight = new HighlightStats(12.0, null, 10, 4, 0.4, 8, 2, 0.25, 0.15, 0.5, true, List.of());
        MatchSeriesStats series = new MatchSeriesStats(
                List.of(new MatchSeriesStats.TrPoint(Instant.parse("2026-10-01T00:00:00Z"), 20900.0),
                        new MatchSeriesStats.TrPoint(Instant.parse("2026-10-02T00:00:00Z"), 21010.5)),
                List.of(new MatchSeriesStats.RoundPoint(1, 2.4, 2.1, 30)));
        StatResult result = new StatResult(null, delta, highlight, winLoss, null, RivalryStats.empty(), series);
        return new AnalysisOutcome.Analyzed(new UserSummary("abc", "s", 21000, 2300, 60, 80.0, 500, 95.5, 2.31, 210.4),
                result, new AnalysisMeta(30, 0, false, 0, 0, 0, 0));
    }

    @Test
    void light와_heavy_7챕터는_서비스가_LLM에_보내는_요청과_같다() {
        StatsService statsService = statsService();
        AnalysisOutcome.Analyzed analyzed = analyzed();

        TrainingPayloadBuilder.Payloads p = new TrainingPayloadBuilder(mock(StatCalculatorFacade.class), statsService)
                .payloads("abc", analyzed);

        StatsResponse stats = statsService.analyzedResponse("abc", analyzed);
        assertThat(MAPPER.writeValueAsString(p.light())).isEqualTo(MAPPER.writeValueAsString(LlmLightRequest.from(stats)));
        Map<String, Object> chapterData = CommentService.buildChapterData(stats);
        assertThat(p.heavy().keySet()).containsExactly(
                "tr_trend", "playstyle", "attack", "defense", "strength_split", "comeback_rate", "session_vs_slope");
        p.heavy().forEach((id, req) -> assertThat(MAPPER.writeValueAsString(req))
                .isEqualTo(MAPPER.writeValueAsString(new LlmHeavyChapterRequest(id, chapterData.get(id)))));
        assertThat(p.matchCount()).isEqualTo(30);
    }

    @Test
    void 화면용_값과_null_후보는_payload에_들어가지_않는다() {
        TrainingPayloadBuilder.Payloads p = new TrainingPayloadBuilder(mock(StatCalculatorFacade.class), statsService())
                .payloads("abc", analyzed());

        String light = MAPPER.writeValueAsString(p.light());
        assertThat(light).doesNotContain("my_avg", "opp_avg", "recent_form", "strength_split", "tr_trend_basis");
        assertThat(MAPPER.writeValueAsString(p.heavy().get("attack")))
                .isEqualTo("{\"chapter_id\":\"attack\",\"data\":{\"delta_app\":0.02,\"delta_weighted_app\":1.4}}");
        assertThat(MAPPER.writeValueAsString(p.heavy().get("strength_split")))
                .isEqualTo("{\"chapter_id\":\"strength_split\",\"data\":{}}");
    }

    @Test
    void 라이트_코멘트_요청은_calc_선정_결과를_한글_영역과_화면_원값으로_바꾼다() {
        AnalysisOutcome.Analyzed base = analyzed();
        HighlightJudgement j = new HighlightJudgement("ref-2026-10-15-g2", "t5", List.of(), List.of(
                pick("delta_app", 0.02, HighlightZone.HIGH),
                pick("delta_comeback", 0.31, HighlightZone.NORMAL),   // 판정값(축소값)
                pick("session_vs_slope", null, HighlightZone.NONE)));
        AnalysisOutcome.Analyzed analyzed = new AnalysisOutcome.Analyzed(base.summary(),
                base.result().withHighlightJudgement(j), base.meta());

        TrainingPayloadBuilder.Payloads p = new TrainingPayloadBuilder(mock(StatCalculatorFacade.class), statsService())
                .payloads("abc", analyzed);

        assertThat(p.judgement()).isSameAs(j);
        // delta_comeback은 축소값이 아니라 화면 원값(comeback_rate − against = 0.4 − 0.25), 영역 none은 뺀다
        assertThat(p.lightItems()).extracting(TrainingPayloadBuilder.LightItem::stat, TrainingPayloadBuilder.LightItem::zone)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("delta_app", "높음"),
                        org.assertj.core.groups.Tuple.tuple("delta_comeback", "보통"));
        assertThat(p.lightItems().get(0).value()).isEqualTo(0.02);
        assertThat(p.lightItems().get(1).value()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
    }

    private static JudgedMetric pick(String key, Double value, HighlightZone zone) {
        return new JudgedMetric(key, key, value, -0.1, 0.1, zone, 1.0, null);
    }
}
