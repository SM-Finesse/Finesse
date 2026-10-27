package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.client.LlmClient;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.StatsLoadProperties;
import com.finesse.backend.dto.LightCommentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * stats가 캐시에 없을 때 light를 먼저 부르는 경우 — stats 재계산이 comment 캐시를 연쇄 무효화하는데,
 * comment-light 캐시 계산 안에서 stats를 부르면 Caffeine이 "Recursive update"로 거부했다(2026-10-06 발견, 500).
 * 실제 Caffeine 캐시로 재현한다.
 */
class CommentCacheOrderTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void stats가_캐시에_없어도_light를_먼저_부를_수_있다() {
        StatCalculatorFacade facade = mock(StatCalculatorFacade.class);
        when(facade.analyze("abc")).thenReturn(new AnalysisOutcome.ColdStartBypass(
                new UserSummary("abc", "z", -1, -1, -1, null, 3, null, null, null), 3,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_TOTAL, null));
        TetrioClient tetrio = mock(TetrioClient.class);
        when(tetrio.fetchUserInfo(any(), any())).thenReturn(TetrioClient.UserInfo.empty());

        CaffeineCacheManager caches = new CaffeineCacheManager(
                CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE);
        StatsService stats = new StatsService(facade, tetrio, caches, new EndpointProperties(20, 40, 60, 120),
                new StatsLoadProperties(2, 5), executor);
        CommentService comments = new CommentService(stats, mock(LlmClient.class), caches, executor,
                mock(LlmProperties.class), new EndpointProperties(20, 40, 60, 120));

        LightCommentResponse first = comments.getLight("ABC");
        LightCommentResponse cached = comments.getLight("abc");

        assertThat(first.highlights()).isEmpty(); // 콜드스타트 안내 — LLM 미호출
        assertThat(cached).isSameAs(first);       // 두 번째는 comment-light 캐시
    }

    @Test
    void 하이라이트가_모자란_light_응답은_보여주되_캐시하지_않는다() {
        StatsService stats = org.mockito.Mockito.mock(StatsService.class);
        com.finesse.backend.dto.StatsResponse.DeltaMetrics delta = new com.finesse.backend.dto.StatsResponse.DeltaMetrics(
                null, null, null, null, null, null, null, null, null, null, null, 0.0);
        when(stats.getStats("abc", false)).thenReturn(new com.finesse.backend.dto.StatsResponse("abc", false, 30,
                java.time.Instant.now(), null,
                new com.finesse.backend.dto.StatsResponse.FixedMetrics(0.5, java.util.List.of(), java.util.List.of()),
                delta, null, null, java.util.Map.of()));
        LlmClient llm = mock(LlmClient.class);
        when(llm.callLight(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(new LightCommentResponse("요약",
                java.util.List.of(new LightCommentResponse.Highlight("delta_plonk", "s"))));
        CaffeineCacheManager caches = new CaffeineCacheManager(
                CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE);
        CommentService comments = new CommentService(stats, llm, caches, executor,
                mock(LlmProperties.class), new EndpointProperties(20, 40, 60, 120));

        assertThat(comments.getLight("abc").highlights()).hasSize(1);
        comments.getLight("abc");

        org.mockito.Mockito.verify(llm, org.mockito.Mockito.times(2)).callLight(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 같은_유저_light가_만드는_중에_또_오면_LLM을_다시_부르지_않고_같은_결과를_준다() throws Exception {
        // QA 2차 BUG-02 확인 — 프론트가 로딩 중 화면을 바꿔 같은 요청을 다시 보내도 LLM 호출은 1번(Caffeine cache.get)
        StatsService stats = org.mockito.Mockito.mock(StatsService.class);
        com.finesse.backend.dto.StatsResponse.DeltaMetrics delta = new com.finesse.backend.dto.StatsResponse.DeltaMetrics(
                null, null, null, null, null, null, null, null, null, null, null, 0.0);
        when(stats.getStats("abc", false)).thenReturn(new com.finesse.backend.dto.StatsResponse("abc", false, 30,
                java.time.Instant.now(), null,
                new com.finesse.backend.dto.StatsResponse.FixedMetrics(0.5, java.util.List.of(), java.util.List.of()),
                delta, null, null, java.util.Map.of()));
        java.util.concurrent.CountDownLatch llmStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        LlmClient llm = mock(LlmClient.class);
        when(llm.callLight(any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            llmStarted.countDown();
            release.await(); // 첫 요청이 LLM을 기다리는 동안 두 번째 요청이 들어온다
            return new LightCommentResponse("요약", java.util.List.of(
                    new LightCommentResponse.Highlight("delta_plonk", "s1"),
                    new LightCommentResponse.Highlight("delta_app", "s2"),
                    new LightCommentResponse.Highlight("session_vs_slope", "s3")), 0);
        });
        CaffeineCacheManager caches = new CaffeineCacheManager(
                CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE);
        CommentService comments = new CommentService(stats, llm, caches, executor,
                mock(LlmProperties.class), new EndpointProperties(20, 40, 60, 120));

        var first = executor.submit(() -> comments.getLight("abc"));
        llmStarted.await();
        var second = executor.submit(() -> comments.getLight("abc"));
        Thread.sleep(300); // 두 번째 요청이 캐시 계산을 기다리기 시작할 시간
        release.countDown();

        assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.times(1)).callLight(any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
