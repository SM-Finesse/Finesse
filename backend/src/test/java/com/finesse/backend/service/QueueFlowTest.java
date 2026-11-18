package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.client.LlmClient;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.config.StatsLoadProperties;
import com.finesse.backend.controller.CommentController;
import com.finesse.backend.controller.QueueController;
import com.finesse.backend.controller.StatsController;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.GlobalExceptionHandler;
import com.finesse.backend.exception.QueuedException;
import com.finesse.backend.queue.TicketRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대기열 켬(app.queue.enabled=true) 흐름 — 「전적 검색 대기열 문서」 3·4·5장을 서비스·컨트롤러까지 이어서 확인.
 */
class QueueFlowTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final QueueProperties queueOn = QueueProperties.enabledDefaults();
    private final TicketRegistry registry = new TicketRegistry(queueOn, System::nanoTime, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static CaffeineCacheManager caches() {
        return new CaffeineCacheManager(CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE,
                CacheConfig.COMMENT_HEAVY_CACHE, CacheConfig.COMMENT_LIGHT_PREPARED_CACHE, CacheConfig.COMMENT_HEAVY_PREPARED_CACHE);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("시간 초과");
            }
            Thread.sleep(20);
        }
    }

    // ---- stats ----

    @Test
    void 캐시_히트는_줄을_서지_않고_1분_안의_갱신은_캐시를_돌려준다() {
        StatCalculatorFacade facade = mock(StatCalculatorFacade.class);
        when(facade.analyze("abc")).thenReturn(new AnalysisOutcome.ColdStartBypass(
                new UserSummary("abc", "z", -1, -1, -1, null, 3, null, null, null), 3,
                AnalysisOutcome.ColdStartReason.FEW_GAMES_TOTAL, null));
        TetrioClient tetrio = mock(TetrioClient.class);
        when(tetrio.fetchUserInfo(any(), any())).thenReturn(TetrioClient.UserInfo.empty());
        StatsService stats = new StatsService(facade, tetrio, caches(), new EndpointProperties(20, 40, 60, 120),
                new StatsLoadProperties(2, 5, 6, 2), executor, 0.3, queueOn, registry, null);
        try {
            StatsResponse first = stats.getStats("ABC", false, "ip"); // 수집 자리가 비어 있음 — 예상 대기 0, 요청 안에서
            StatsResponse hit = stats.getStats("abc", false, "ip");
            StatsResponse refreshed = stats.getStats("abc", true, "ip"); // 방금 갱신 — 줄을 세우지 않음

            assertThat(hit).isSameAs(first);
            assertThat(refreshed).isSameAs(first);
            verify(facade, times(1)).analyze("abc");
            assertThat(registry.size()).as("요청 안에서 끝나면 대기표 없음").isZero();
        } finally {
            stats.stopQueue();
        }
    }

    @Test
    void stats_대기표는_202와_Retry_After_취소는_204_없는_대기표는_404() throws Exception {
        StatsService stats = mock(StatsService.class);
        QueueTicketResponse ticket = QueueTicketResponse.issued("q_0123456789abcdef01234567", "stats", 7, 35, 2);
        when(stats.getStats(anyString(), anyBoolean(), any())).thenThrow(new QueuedException(ticket));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StatsController(stats), new QueueController(registry))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        String body = mvc.perform(get("/api/v1/stats/someone"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "2"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("q_0123456789abcdef01234567").contains("\"position\":7").doesNotContain("someone");

        mvc.perform(get("/api/v1/queue/q_missing")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/queue/q_missing")).andExpect(status().isNotFound());
    }

    // ---- light ----

    private static StatsResponse statsWithPicks() {
        StatsResponse.DeltaMetrics delta = new StatsResponse.DeltaMetrics(
                12.5, null, new StatsResponse.PlaystyleRelative(0.1, -0.22, 0.03, -0.17),
                new StatsResponse.Attack(0.092, -1.34, null, null), new StatsResponse.Defense(-0.07, -14.53, null, null),
                -0.2, null, 0.31, 0.29, 0.0356, new StatsResponse.ComebackSamples(41, 13, 24, 7), 4.61);
        List<StatsResponse.JudgedMetric> picks = List.of(
                new StatsResponse.JudgedMetric("session_vs_slope", "session_vs_slope", 4.61, -0.1, 0.1, "high", 1.0, null),
                new StatsResponse.JudgedMetric("delta_app", "delta_app", 0.092, -0.1, 0.1, "high", 1.0, null),
                new StatsResponse.JudgedMetric("delta_cheese_index", "delta_cheese_index", -14.53, -0.1, 0.1, "low", 1.0, null));
        return new StatsResponse("abc", false, 300, Instant.now(), null,
                new StatsResponse.FixedMetrics(0.5, List.of(), List.of()), delta,
                new StatsResponse.RoundCurves(List.of(), List.of(), List.of()),
                new StatsResponse.Rivals(List.of(), 1, 20, 0), Map.of(),
                new StatsResponse.HighlightJudgement("ref", "t5", List.of(), picks));
    }

    private CommentService commentService(StatsService statsService, LlmClient llm) {
        LlmProperties props = mock(LlmProperties.class);
        when(props.servers()).thenReturn(List.of("a", "b"));
        return new CommentService(statsService, llm, caches(), executor, props, new EndpointProperties(20, 40, 60, 120),
                LightCommentProperties.defaults(), queueOn, registry);
    }

    @Test
    void Q8_LLM이_밀리면_light는_202_stage_comment_차례가_오면_만들어_두고_done_뒤에는_LLM을_다시_부르지_않는다() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(40.0); // 20초 > 15초
        when(llm.jobWorkSeconds(any())).thenReturn(6.0);
        // 대체 문장 1개 — comment-light에는 넣지 않는 결과(준비 칸으로)
        when(llm.callLightComments(any(), anyLong())).thenReturn(new LlmClient.LightCommentOutcome("요약", List.of(
                new LightCommentResponse.Highlight("session_vs_slope", "a"),
                new LightCommentResponse.Highlight("delta_app", "b"),
                new LightCommentResponse.Highlight("delta_cheese_index", "c")), 0, 1, 0, "v"));
        CommentService service = commentService(statsService, llm);
        try {
            QueuedException queued = org.junit.jupiter.api.Assertions.assertThrows(QueuedException.class,
                    () -> service.getLight("abc", "ip"));
            assertThat(queued.ticket().stage()).isEqualTo(QueueTicketResponse.STAGE_COMMENT);
            verify(llm, times(0)).callLightComments(any(), anyLong());

            when(llm.pendingWorkSeconds()).thenReturn(0.0); // LLM이 풀림 — 0.5초마다 판정해 차례대로 시작
            String id = queued.ticket().ticketId();
            await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(id).status()));

            LightCommentResponse ready = service.getLight("abc", "ip");
            LightCommentResponse again = service.getLight("abc", "ip2"); // 같은 유저의 다른 대기표(3.6절)도 같은 결과
            assertThat(ready.lightSummary()).isEqualTo("요약");
            assertThat(again).isEqualTo(ready);
            verify(llm, times(1)).callLightComments(any(), anyLong()); // done 뒤 다시 불러도 LLM은 한 번
        } finally {
            service.stopQueue();
        }
    }

    @Test
    void 대체_문장이_섞인_준비_결과는_2분이_지나면_지금처럼_다시_만든다() throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        TicketRegistry clocked = new TicketRegistry(queueOn, now::get, false);
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(40.0);
        when(llm.jobWorkSeconds(any())).thenReturn(6.0);
        when(llm.callLightComments(any(), anyLong())).thenReturn(new LlmClient.LightCommentOutcome("요약", List.of(
                new LightCommentResponse.Highlight("session_vs_slope", "a"),
                new LightCommentResponse.Highlight("delta_app", "b"),
                new LightCommentResponse.Highlight("delta_cheese_index", "c")), 0, 1, 0, "v"));
        LlmProperties props = mock(LlmProperties.class);
        when(props.servers()).thenReturn(List.of("a", "b"));
        CommentService service = new CommentService(statsService, llm, caches(), executor, props,
                new EndpointProperties(20, 40, 60, 120), LightCommentProperties.defaults(), queueOn, clocked);
        try {
            QueuedException queued = org.junit.jupiter.api.Assertions.assertThrows(QueuedException.class,
                    () -> service.getLight("abc", "ip"));
            when(llm.pendingWorkSeconds()).thenReturn(0.0);
            await(() -> QueueTicketResponse.STATUS_DONE.equals(clocked.view(queued.ticket().ticketId()).status()));
            service.getLight("abc", "ip");
            verify(llm, times(1)).callLightComments(any(), anyLong());

            now.addAndGet(TimeUnit.SECONDS.toNanos(121)); // 끝난 대기표 보관 2분이 지남
            service.getLight("abc", "ip"); // 한가함 — 요청 안에서 다시 만든다
            verify(llm, times(2)).callLightComments(any(), anyLong());
        } finally {
            service.stopQueue();
        }
    }

    @Test
    void 라이트_미리_만들기는_대기표로_수집한_유저만() throws Exception {
        StatsService statsService = mock(StatsService.class);
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(0.0);
        when(llm.callLightComments(any(), anyLong())).thenReturn(new LlmClient.LightCommentOutcome("요약", List.of(
                new LightCommentResponse.Highlight("session_vs_slope", "a"),
                new LightCommentResponse.Highlight("delta_app", "b"),
                new LightCommentResponse.Highlight("delta_cheese_index", "c")), 0, 0, 0, "v"));
        CommentService service = commentService(statsService, llm);
        try {
            service.onStatsCollected(new StatsCollectedEvent("inline", statsWithPicks(), false));
            Thread.sleep(300);
            verify(llm, times(0)).callLightComments(any(), anyLong());

            service.onStatsCollected(new StatsCollectedEvent("ticketed", statsWithPicks(), true));
            await(() -> {
                try {
                    verify(llm, times(1)).callLightComments(any(), anyLong());
                    return true;
                } catch (AssertionError e) {
                    return false;
                }
            });
            // 미리 만든 결과는 이어서 온 light 요청이 그대로 받는다 — LLM을 다시 부르지 않음
            when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
            assertThat(service.getLight("ticketed", "ip").lightSummary()).isEqualTo("요약");
            verify(llm, times(1)).callLightComments(any(), anyLong());
        } finally {
            service.stopQueue();
        }
    }

    @Test
    void LLM이_한가하면_light는_지금처럼_요청_안에서_만든다() {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(4.0);
        when(llm.callLightComments(any(), anyLong())).thenReturn(new LlmClient.LightCommentOutcome("요약", List.of(
                new LightCommentResponse.Highlight("session_vs_slope", "a"),
                new LightCommentResponse.Highlight("delta_app", "b"),
                new LightCommentResponse.Highlight("delta_cheese_index", "c")), 0, 0, 0, "v"));
        CommentService service = commentService(statsService, llm);
        try {
            assertThat(service.getLight("abc", "ip").highlights()).hasSize(3);
            assertThat(registry.size()).isZero();
        } finally {
            service.stopQueue();
        }
    }

    // ---- heavy ----

    @Test
    void Q13_Q21_LLM이_밀리면_heavy는_200_스트림_안에_event_queued_1건_후_종료() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(100.0); // 50초 > 30초
        when(llm.jobWorkSeconds(any())).thenReturn(16.0);
        CommentService service = commentService(statsService, llm);
        try {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            MvcResult started = mvc.perform(get("/api/v1/comment/abc").param("scope", "heavy"))
                    .andExpect(request().asyncStarted()).andReturn();
            MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
            String body = done.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

            assertThat(body).startsWith("event:start");
            assertThat(body).contains("event:queued").contains("\"stage\":\"heavy\"").doesNotContain("event:chapter");
            verify(llm, times(0)).callHeavyChapter(anyString(), any(), anyLong());
        } finally {
            service.stopQueue();
        }
    }

    @Test
    void heavy_대기표_done_뒤_다시_열면_기다리는_동안_만든_8챕터가_LLM_없이_바로_온다() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenReturn(statsWithPicks());
        LlmClient llm = mock(LlmClient.class);
        when(llm.assignableServers()).thenReturn(2);
        when(llm.pendingWorkSeconds()).thenReturn(100.0);
        when(llm.jobWorkSeconds(any())).thenReturn(16.0);
        when(llm.callHeavyChapter(anyString(), any(), anyLong())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            // attack만 실패 — 성공 챕터만 캐시하는 규칙 때문에 캐시만으로는 8챕터가 다 차지 않는 경우
            return "attack".equals(id)
                    ? new com.finesse.backend.dto.HeavyCommentResponse.ChapterResult(id, "failed", null, 3)
                    : new com.finesse.backend.dto.HeavyCommentResponse.ChapterResult(id, "ok", id + " 문장", 0);
        });
        CommentService service = commentService(statsService, llm);
        try {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            String first = heavyBody(mvc);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("q_[0-9a-f]{24}").matcher(first);
            assertThat(m.find()).as(first).isTrue();
            String ticketId = m.group();

            when(llm.pendingWorkSeconds()).thenReturn(0.0); // 차례가 옴 — 스트림 없이 8챕터를 만든다
            await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(ticketId).status()));
            verify(llm, times(7)).callHeavyChapter(anyString(), any(), anyLong()); // rivals는 상대가 없어 LLM 없이

            String reopened = heavyBody(mvc);
            assertThat(reopened.split("event:chapter", -1)).hasSize(9); // 8챕터
            assertThat(reopened).contains("event:done").doesNotContain("event:queued");
            verify(llm, times(7)).callHeavyChapter(anyString(), any(), anyLong()); // 다시 열어도 LLM을 또 부르지 않음
        } finally {
            service.stopQueue();
        }
    }

    private static String heavyBody(MockMvc mvc) throws Exception {
        MvcResult started = mvc.perform(get("/api/v1/comment/abc").param("scope", "heavy"))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void Q22_heavy에서_stats_캐시가_없고_수집_예상_대기가_5초를_넘으면_event_queued_stage_stats() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false), any())).thenThrow(new QueuedException(
                QueueTicketResponse.issued("q_aaaaaaaaaaaaaaaaaaaaaaaa", "stats", 4, 10, 2)));
        CommentService service = commentService(statsService, mock(LlmClient.class));
        try {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            MvcResult started = mvc.perform(get("/api/v1/comment/abc").param("scope", "heavy"))
                    .andExpect(request().asyncStarted()).andReturn();
            String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse()
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

            assertThat(body).contains("event:queued").contains("\"stage\":\"stats\"").contains("q_aaaaaaaaaaaaaaaaaaaaaaaa");
        } finally {
            service.stopQueue();
        }
    }
}
