package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET /api/v1/comment/{username}?scope=light|heavy 오케스트레이션.
 * Finesse-API명세서 4.2절(흐름), 4.2-1절(heavy 챕터 상세 규칙), 6장(LLM 서버 연동).
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private static final List<String> HEAVY_CHAPTER_IDS = List.of(
            "tr_trend", "playstyle", "attack", "defense",
            "strength_split", "comeback_rate", "session_vs_slope", "rivals"
    );

    private final StatsService statsService;
    private final LlmClient llmClient;
    private final CacheManager cacheManager;
    private final ExecutorService llmExecutor;
    private final LlmProperties llmProperties;
    private final EndpointProperties endpointProperties;

    public CommentService(StatsService statsService, LlmClient llmClient, CacheManager cacheManager,
                           ExecutorService llmExecutor, LlmProperties llmProperties,
                           EndpointProperties endpointProperties) {
        this.statsService = statsService;
        this.llmClient = llmClient;
        this.cacheManager = cacheManager;
        this.llmExecutor = llmExecutor;
        this.llmProperties = llmProperties;
        this.endpointProperties = endpointProperties;
    }

    public LightCommentResponse getLight(String username) {
        String normalized = username.toLowerCase();
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
        if (cache == null) {
            return computeLight(normalized);
        }
        return cache.get(normalized, () -> computeLight(normalized));
    }

    public HeavyCommentResponse getHeavy(String username) {
        String normalized = username.toLowerCase();
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_CACHE);
        if (cache == null) {
            return computeHeavy(normalized);
        }
        return cache.get(normalized, () -> computeHeavy(normalized));
    }

    private LightCommentResponse computeLight(String normalized) {
        StatsResponse stats = statsService.getStats(normalized, false);
        if (stats.coldStart()) {
            // FR-02/기능 명세서 3.3절 — 콜드스타트는 LLM 미호출, 즉시 안내 (재요청 대상 아님)
            return new LightCommentResponse("최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.", List.of());
        }
        // light 엔드포인트 상한 — 타임아웃 기준 문서(23번) 9절, stats 완료 이후부터 기산
        long deadline = System.nanoTime() + endpointProperties.lightSeconds() * 1_000_000_000L;
        LlmLightRequest request = new LlmLightRequest(stats.fixedMetrics(), stats.deltaMetrics());
        return llmClient.callLight(request, deadline);
    }

    private HeavyCommentResponse computeHeavy(String normalized) {
        StatsResponse stats = statsService.getStats(normalized, false);
        if (stats.coldStart()) {
            List<HeavyCommentResponse.ChapterResult> results = HEAVY_CHAPTER_IDS.stream()
                    .map(id -> new HeavyCommentResponse.ChapterResult(id, HeavyCommentResponse.STATUS_FAILED, null, 0))
                    .toList();
            return new HeavyCommentResponse(results);
        }

        // 전체(8챕터) 처리 시간 상한 — 타임아웃 기준 문서(23번) 9절: 서버 대수에 따라 다르게 둔다.
        // MVP(서버 2대 이상, 서버당 순차 처리)는 60초, 단일 서버(1대)는 120초.
        int serverCount = llmProperties.servers().size();
        int heavyTimeoutSeconds = serverCount <= 1
                ? endpointProperties.heavySingleServerSeconds()
                : endpointProperties.heavySeconds();
        long deadline = System.nanoTime() + heavyTimeoutSeconds * 1_000_000_000L;

        Map<String, Object> chapterData = buildChapterData(stats);
        Map<String, CompletableFuture<HeavyCommentResponse.ChapterResult>> futures = new LinkedHashMap<>();
        for (String chapterId : HEAVY_CHAPTER_IDS) {
            Object data = chapterData.get(chapterId);
            futures.put(chapterId, CompletableFuture.supplyAsync(
                    () -> llmClient.callHeavyChapter(chapterId, data, deadline), llmExecutor));
        }

        long startedAt = System.currentTimeMillis();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
        try {
            all.get(heavyTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 전체 상한 시간 초과 — 엔드포인트 타임아웃이 개별 챕터 재시도보다 우선 (4.2-1절)
        } catch (Exception e) {
            // 개별 실패는 각 future에서 이미 흡수됨 (LlmClient가 예외를 던지지 않으므로 여기 도달할 일은 드묾)
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("heavy 코멘트 8챕터 처리 종료: {}ms (한도 {}s, 서버 {}대) ({}) - {}",
                elapsedMs, heavyTimeoutSeconds, serverCount, normalized, HEAVY_CHAPTER_IDS);

        List<HeavyCommentResponse.ChapterResult> results = HEAVY_CHAPTER_IDS.stream()
                .map(id -> {
                    CompletableFuture<HeavyCommentResponse.ChapterResult> f = futures.get(id);
                    if (f.isDone() && !f.isCompletedExceptionally()) {
                        return f.join();
                    }
                    return new HeavyCommentResponse.ChapterResult(id, HeavyCommentResponse.STATUS_TIMEOUT, null, null);
                })
                .toList();
        return new HeavyCommentResponse(results);
    }

    private Map<String, Object> buildChapterData(StatsResponse stats) {
        StatsResponse.DeltaMetrics d = stats.deltaMetrics();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tr_trend", d != null ? d.trTrendDelta() : null);
        map.put("playstyle", d != null ? d.playstyleRelative() : null);
        map.put("attack", d != null ? d.attack() : null);
        map.put("defense", d != null ? d.defense() : null);
        map.put("strength_split", d != null ? d.strengthSplit() : null);
        map.put("comeback_rate", d != null ? d.comebackRate() : null);
        map.put("session_vs_slope", d != null ? d.sessionVsSlope() : null);
        map.put("rivals", stats.rivals()); // 라이벌 챕터는 라이벌 목록 요약 데이터 (4.2절)
        return map;
    }
}
