package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.controller.CommentController;
import com.finesse.backend.exception.GlobalExceptionHandler;
import com.finesse.backend.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * heavy 스트림은 열리자마자 start 이벤트를 보낸다 — EventSource는 ": ping" 주석을 화면 코드에 넘기지 않아서
 * stats·첫 챕터를 기다리는 동안 프론트의 "첫 이벤트 15초" 판정에 걸리지 않게 (QA 검수요청서 R-05).
 */
class HeavyStreamStartTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void 스트림의_첫_이벤트는_start이고_그_뒤에_원래_이벤트가_온다() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false))).thenThrow(new UserNotFoundException("ghost"));
        LlmProperties props = mock(LlmProperties.class);
        when(props.servers()).thenReturn(List.of("a", "b"));
        CommentService service = new CommentService(statsService, mock(LlmClient.class),
                new CaffeineCacheManager(CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE),
                executor, props, new EndpointProperties(20, 40, 60, 120));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult started = mvc.perform(get("/api/v1/comment/ghost").param("scope", "heavy"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 독립 MockMvc는 앱의 SNAKE_CASE 설정을 쓰지 않는다(실제 응답 키는 total_chapters) — 값으로 확인
        assertThat(body).startsWith("event:start\ndata:{");
        assertThat(body.substring(0, body.indexOf("\n\n"))).endsWith(":8}");
        assertThat(body.indexOf("event:error")).as("start 다음에 원래 이벤트(여기선 없는 유저 오류)").isGreaterThan(0);
        assertThat(body).as("오류 이벤트 메시지에도 유저명이 없다").doesNotContain("ghost\"")
                .contains(GlobalExceptionHandler.USER_NOT_FOUND_MESSAGE);
    }
}
