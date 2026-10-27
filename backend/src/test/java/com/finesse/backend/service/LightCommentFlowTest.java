package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmLightComment;
import com.finesse.backend.dto.StatsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 라이트 코멘트 light-v5.2 흐름 — calc가 고른 지표로 LLM 요청을 만들고, 대체 문장이 섞이면 캐시하지 않는다.
 */
class LightCommentFlowTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static StatsResponse.JudgedMetric pick(String key, String zone, Double value) {
        return new StatsResponse.JudgedMetric(key, key, value, -0.1, 0.1, zone, 1.0, null);
    }

    private static StatsResponse stats(List<StatsResponse.JudgedMetric> picks) {
        StatsResponse.DeltaMetrics delta = new StatsResponse.DeltaMetrics(
                12.5, null, new StatsResponse.PlaystyleRelative(0.1, -0.22, 0.03, -0.17),
                new StatsResponse.Attack(0.092, -1.34, null, null), new StatsResponse.Defense(-0.07, -14.53, null, null),
                -0.2, null, 0.31, 0.29, 0.0356, new StatsResponse.ComebackSamples(41, 13, 24, 7), 4.61);
        return new StatsResponse("abc", false, 300, Instant.now(), null,
                new StatsResponse.FixedMetrics(0.5, List.of(), List.of()), delta,
                new StatsResponse.RoundCurves(List.of(), List.of(), List.of()),
                new StatsResponse.Rivals(List.of(), 1, 20, 0), Map.of(),
                new StatsResponse.HighlightJudgement("ref-2026-10-15-g2", "t5", List.of(), picks));
    }

    @Test
    void 선정된_지표를_한글_영역과_화면_원값으로_바꾼다() {
        StatsResponse s = stats(List.of(
                pick("session_vs_slope", "high", 4.61),
                pick("delta_comeback", "normal", 0.0521),          // 판정값(축소값) — 요청에는 원값 0.0356
                pick("delta_cheese_index", "low", -14.53)));

        assertThat(CommentService.lightCommentItems(s)).containsExactly(
                new LlmLightComment.Item("session_vs_slope", 4.61, "높음"),
                new LlmLightComment.Item("delta_comeback", 0.0356, "보통"),
                new LlmLightComment.Item("delta_cheese_index", -14.53, "낮음"));
    }

    @Test
    void 영역이_none이거나_원값이_없는_지표는_뺀다() {
        StatsResponse s = stats(List.of(
                pick("delta_app", "none", null),
                pick("delta_inf_ds", "high", -0.17)));
        assertThat(CommentService.lightCommentItems(s)).extracting(LlmLightComment.Item::stat).containsExactly("delta_inf_ds");
        assertThat(CommentService.lightCommentItems(stats(List.of()))).isEmpty();
    }

    private CommentService service(StatsResponse stats, LlmClient llm, LightCommentProperties props) {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false))).thenReturn(stats);
        return new CommentService(statsService, llm,
                new CaffeineCacheManager(CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE),
                executor, mock(LlmProperties.class), new EndpointProperties(20, 40, 60, 120), props);
    }

    private static LlmClient.LightCommentOutcome outcome(int fallbacks) {
        return new LlmClient.LightCommentOutcome("요약", List.of(
                new LightCommentResponse.Highlight("session_vs_slope", "a"),
                new LightCommentResponse.Highlight("delta_app", "b"),
                new LightCommentResponse.Highlight("delta_cheese_index", "c")), 0, fallbacks, 0, "light-v5.2-guide31");
    }

    private static final List<StatsResponse.JudgedMetric> THREE = List.of(
            pick("session_vs_slope", "high", 4.61), pick("delta_app", "high", 0.092), pick("delta_cheese_index", "low", -14.53));

    @Test
    void 대체_문장이_없으면_캐시하고_예전_light_호출은_쓰지_않는다() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.callLightComments(any(), anyLong())).thenReturn(outcome(0));
        CommentService service = service(stats(THREE), llm, LightCommentProperties.defaults());

        LightCommentResponse first = service.getLight("abc");
        service.getLight("abc");

        assertThat(first.lightSummary()).isEqualTo("요약");
        assertThat(first.highlights()).extracting(LightCommentResponse.Highlight::stat)
                .containsExactly("session_vs_slope", "delta_app", "delta_cheese_index");
        verify(llm, times(1)).callLightComments(any(), anyLong());
        verify(llm, never()).callLight(any(), anyLong());
    }

    @Test
    void 대체_문장이_섞이면_보여주되_캐시하지_않는다() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.callLightComments(any(), anyLong())).thenReturn(outcome(1));
        CommentService service = service(stats(THREE), llm, LightCommentProperties.defaults());

        service.getLight("abc");
        service.getLight("abc");

        verify(llm, times(2)).callLightComments(any(), anyLong());
    }

    @Test
    void 설정으로_끄면_예전_한_번_호출로_돌아간다() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.callLight(any(), anyLong())).thenReturn(new LightCommentResponse("예전", List.of(), 0));
        CommentService service = service(stats(THREE), llm, new LightCommentProperties(false, null, null, null, null));

        assertThat(service.getLight("abc").lightSummary()).isEqualTo("예전");
        verify(llm, never()).callLightComments(any(), anyLong());
    }
}
