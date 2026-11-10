package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.controller.CommentController;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * LLM 서버가 대체 문장(fallback=true)을 보낸 챕터는 화면에는 보내되 캐시하지 않는다 — 다음 요청 때 LLM으로 다시 시도.
 * rivals는 처음부터 서버 문장을 쓰는 챕터라 fallback=true여도 캐시한다 (LLM 헤비 v4.3, 10/20).
 */
class HeavyFallbackCacheTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static final Set<String> SERVER_SENTENCE = Set.of("tr_trend", "rivals");

    private static StatsResponse stats() {
        StatsResponse.DeltaMetrics delta = new StatsResponse.DeltaMetrics(
                12.5, null, new StatsResponse.PlaystyleRelative(0.1, -0.22, 0.03, -0.17),
                new StatsResponse.Attack(-0.05, -1.34, null, null), new StatsResponse.Defense(-0.07, -2.56, null, null),
                -0.2, null, 0.31, 0.29, 0.02, new StatsResponse.ComebackSamples(41, 13, 24, 7), 0.55);
        return new StatsResponse("abc", false, 300, Instant.now(), null,
                new StatsResponse.FixedMetrics(0.5, List.of(), List.of()), delta,
                new StatsResponse.RoundCurves(List.of(), List.of(), List.of()),
                new StatsResponse.Rivals(List.of(new StatsResponse.RivalItem("pla***_1", 9, 5, 4, null)), 1, 20, 1),
                Map.of());
    }

    private static String heavy(MockMvc mvc) throws Exception {
        MvcResult started = mvc.perform(get("/api/v1/comment/abc").param("scope", "heavy"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 대체_문장_챕터는_보내지만_캐시하지_않고_다음_요청_때_다시_부른다() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false))).thenReturn(stats());
        LlmClient llm = mock(LlmClient.class);
        when(llm.callHeavyChapter(anyString(), any(), anyLong())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return new HeavyCommentResponse.ChapterResult(id, HeavyCommentResponse.STATUS_OK, "각주 " + id, 0,
                    SERVER_SENTENCE.contains(id));
        });
        LlmProperties props = mock(LlmProperties.class);
        when(props.servers()).thenReturn(List.of("a", "b"));
        CommentService service = new CommentService(statsService, llm,
                new CaffeineCacheManager(CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE),
                executor, props, new EndpointProperties(20, 40, 60, 120));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String first = heavy(mvc);
        String second = heavy(mvc);

        assertThat(first).contains("각주 tr_trend").doesNotContain("fallback");   // 화면에는 보내고, 내부 표시는 응답에 안 나감
        assertThat(second).contains("각주 tr_trend");
        verify(llm, times(2)).callHeavyChapter(eq("tr_trend"), any(), anyLong());     // 대체 문장 → 다시 부름
        verify(llm, times(1)).callHeavyChapter(eq("rivals"), any(), anyLong());       // rivals 서버 문장은 캐시
        verify(llm, times(1)).callHeavyChapter(eq("attack"), any(), anyLong());       // LLM 문장은 캐시
    }

    @Test
    void rivals가_아닌_챕터의_fallback만_대체_문장으로_친다() {
        assertThat(CommentService.isLlmFallback(
                new HeavyCommentResponse.ChapterResult("attack", "ok", "s", 0, true))).isTrue();
        assertThat(CommentService.isLlmFallback(
                new HeavyCommentResponse.ChapterResult("rivals", "ok", "s", 0, true))).isFalse();
        assertThat(CommentService.isLlmFallback(
                new HeavyCommentResponse.ChapterResult("attack", "ok", "s", 0))).isFalse();
    }
}
