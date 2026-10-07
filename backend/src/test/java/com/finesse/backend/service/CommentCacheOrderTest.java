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
}
