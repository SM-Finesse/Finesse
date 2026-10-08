package com.finesse.backend.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.StatsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * heavy 챕터별 LLM 입력 data — LLM/AI 파트 설계 v1.2 5.3·7.3·7.4절, 10/7 백엔드 결정(역전 표본 수 포함,
 * 라이벌 상위 5명, 반복 조우 상대가 없으면 LLM 없이 고정 문구 ok).
 */
class HeavyChapterDataTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static StatsResponse stats(int rivalCount, Double strengthSplit) {
        StatsResponse.DeltaMetrics delta = new StatsResponse.DeltaMetrics(
                12.5, null,
                new StatsResponse.PlaystyleRelative(0.1, -0.22, null, -0.17),
                new StatsResponse.Attack(-0.05, -1.34),
                new StatsResponse.Defense(-0.07, -2.56),
                strengthSplit, null, 0.31, 0.29, 0.02, new StatsResponse.ComebackSamples(41, 13, 24, 7), 0.55);
        List<StatsResponse.RivalItem> items = IntStream.range(0, rivalCount)
                .mapToObj(i -> new StatsResponse.RivalItem("pla***_" + i, 20 - i, 10, 10 - i, null))
                .toList();
        return new StatsResponse("abc", false, 300, Instant.now(), null,
                new StatsResponse.FixedMetrics(0.5, List.of(), List.of()), delta,
                new StatsResponse.RoundCurves(List.of(), List.of(), List.of()),
                new StatsResponse.Rivals(items, 1, 20, rivalCount), Map.of());
    }

    @Test
    void 모든_챕터_data는_object이고_값이_없는_필드는_뺀다() {
        JsonNode data = mapper.valueToTree(CommentService.buildChapterData(stats(7, null)));

        data.properties().forEach(e -> assertThat(e.getValue().isObject()).as(e.getKey()).isTrue());
        assertThat(data.get("tr_trend").get("tr_trend_delta").asDouble()).isEqualTo(12.5);
        assertThat(data.get("strength_split").isEmpty()).isTrue(); // null이라 키째 빠짐
        assertThat(data.get("playstyle").has("delta_stride")).isFalse();
        assertThat(data.get("session_vs_slope").get("session_vs_slope").asDouble()).isEqualTo(0.55);
    }

    @Test
    void 역전_챕터에는_두_비율과_표본_수를_넣는다() {
        JsonNode comeback = mapper.valueToTree(CommentService.buildChapterData(stats(0, -0.2))).get("comeback_rate");

        assertThat(Set.copyOf(comeback.propertyNames())).containsExactlyInAnyOrder(
                "comeback_rate", "comeback_rate_against", "comeback_opportunities", "comeback_won",
                "comeback_against_opportunities", "comeback_against_allowed");
        assertThat(comeback.get("comeback_opportunities").asInt()).isEqualTo(41);
        assertThat(comeback.get("comeback_won").asInt()).isEqualTo(13);
    }

    @Test
    void 라이벌은_상위_5명만_마지막_대전_시각_없이() {
        JsonNode rivals = mapper.valueToTree(CommentService.buildChapterData(stats(7, null))).get("rivals").get("rivals");

        assertThat(rivals.size()).isEqualTo(CommentService.RIVALS_FOR_LLM);
        assertThat(Set.copyOf(rivals.get(0).propertyNames()))
                .containsExactlyInAnyOrder("nickname_masked", "matches", "wins", "losses");
        assertThat(rivals.get(0).get("nickname_masked").asString()).isEqualTo("pla***_0");
    }

    @Test
    void 반복_조우_상대가_없으면_라이벌_챕터는_LLM_없이_고정_문구로_ok() throws Exception {
        StatsService statsService = mock(StatsService.class);
        when(statsService.getStats(anyString(), eq(false))).thenReturn(stats(0, -0.2));
        LlmClient llm = mock(LlmClient.class);
        when(llm.callHeavyChapter(anyString(), any(), anyLong())).thenAnswer(inv -> new HeavyCommentResponse.ChapterResult(
                inv.getArgument(0), HeavyCommentResponse.STATUS_OK, "각주", 0));
        LlmProperties props = mock(LlmProperties.class);
        when(props.servers()).thenReturn(List.of("a", "b"));
        CaffeineCacheManager caches = new CaffeineCacheManager(
                CacheConfig.STATS_CACHE, CacheConfig.COMMENT_LIGHT_CACHE, CacheConfig.COMMENT_HEAVY_CACHE);
        CommentService service = new CommentService(statsService, llm, caches, executor, props,
                new EndpointProperties(20, 40, 60, 120));

        service.getHeavyStream("abc");

        Cache heavy = caches.getCache(CacheConfig.COMMENT_HEAVY_CACHE);
        for (int i = 0; i < 100 && heavy.get("abc") == null; i++) {
            Thread.sleep(50);
        }
        assertThat(heavy.get("abc")).as("heavy 캐시 저장(스트림 종료)").isNotNull();
        verify(llm, never()).callHeavyChapter(eq("rivals"), any(), anyLong());
        verify(llm).callHeavyChapter(eq("attack"), any(), anyLong());
        assertThat(heavy.get("abc").get().toString()).contains(CommentService.NO_RIVALS_FOOTNOTE);
    }
}
