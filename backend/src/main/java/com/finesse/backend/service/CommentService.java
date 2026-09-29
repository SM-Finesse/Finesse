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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * scope=heavy — 12절 4번(2026-09-29 확정): 챕터가 완료되는 대로 SSE로 개별 전달한다(순차/스트리밍).
     * 전체를 모아 한 번에 응답하던 allOf 방식은 폐기 — 사용자가 채워진 부분부터 바로 볼 수 있게 하기 위함.
     * 캐시 히트면 저장된 8개 챕터를 그대로(빠르게) 스트리밍하고, 미스면 실제 계산하며 하나씩 흘려보낸다.
     */
    public SseEmitter getHeavyStream(String username) {
        String normalized = username.toLowerCase();
        // SseEmitter 자체 타임아웃은 heavy 최악 상한(단일 서버 120s)보다 넉넉히 잡는다 — 실제 컷오프는
        // 아래 heavyTimeoutSeconds(엔드포인트 데드라인)가 담당하므로 여기선 연결이 일찍 끊기지만 않으면 됨.
        SseEmitter emitter = new SseEmitter(150_000L);
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_CACHE);

        HeavyCommentResponse cached = cache != null ? cache.get(normalized, HeavyCommentResponse.class) : null;
        if (cached != null) {
            llmExecutor.execute(() -> streamAndComplete(emitter, cached.chapters()));
            return emitter;
        }

        llmExecutor.execute(() -> computeHeavyStreaming(normalized, emitter, cache));
        return emitter;
    }

    private void streamAndComplete(SseEmitter emitter, List<HeavyCommentResponse.ChapterResult> results) {
        for (HeavyCommentResponse.ChapterResult r : results) {
            if (!sendChapter(emitter, r)) {
                return;
            }
        }
        sendDone(emitter);
        emitter.complete();
    }

    private boolean sendChapter(SseEmitter emitter, HeavyCommentResponse.ChapterResult result) {
        try {
            emitter.send(SseEmitter.event().name("chapter").data(result));
            return true;
        } catch (IOException e) {
            // 클라이언트가 이미 연결을 끊음 — 더 보내지 않는다 (이미 LLM에 보낸 호출은 계속 진행해서 캐싱에는 반영)
            return false;
        }
    }

    /**
     * 서버가 그냥 연결을 닫기만 하면 브라우저 EventSource는 이걸 "끊김"으로 보고 자동 재연결을 시도한다
     * (SSE 스펙 동작) — 그래서 "다 보냈다"는 걸 알리는 이벤트를 명시적으로 하나 보내고, 프론트는 이걸
     * 받으면 자기가 먼저 EventSource를 닫도록 한다. 8개를 다 못 채웠어도(타임아웃 등) 항상 보낸다.
     */
    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException e) {
            // 이미 끊긴 연결 — 무시
        }
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

    /**
     * 8챕터를 각각 dispatch하고, 챕터 하나가 끝날 때마다(thenAccept) 바로 SSE로 전송한다.
     * allOf는 "다 끝났는지" 판정에만 쓰고, 전달 자체는 더 이상 allOf 완료를 기다리지 않는다.
     */
    private void computeHeavyStreaming(String normalized, SseEmitter emitter, Cache cache) {
        StatsResponse stats = statsService.getStats(normalized, false);
        if (stats.coldStart()) {
            List<HeavyCommentResponse.ChapterResult> results = HEAVY_CHAPTER_IDS.stream()
                    .map(id -> new HeavyCommentResponse.ChapterResult(id, HeavyCommentResponse.STATUS_FAILED, null, 0))
                    .toList();
            streamAndComplete(emitter, results);
            return;
        }

        // 전체(8챕터) 처리 시간 상한 — 타임아웃 기준 문서(23번) 9절: 서버 대수에 따라 다르게 둔다.
        // MVP(서버 2대 이상, 서버당 순차 처리)는 60초, 단일 서버(1대)는 120초.
        int serverCount = llmProperties.servers().size();
        int heavyTimeoutSeconds = serverCount <= 1
                ? endpointProperties.heavySingleServerSeconds()
                : endpointProperties.heavySeconds();
        long deadline = System.nanoTime() + heavyTimeoutSeconds * 1_000_000_000L;

        Map<String, Object> chapterData = buildChapterData(stats);
        Map<String, HeavyCommentResponse.ChapterResult> collected = new ConcurrentHashMap<>();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        List<CompletableFuture<Void>> allDone = new ArrayList<>();
        // 챕터 선점+전송과 마감(timeout 채우기+done)을 한 락으로 묶는다 — 선점한 챕터가 done 뒤에 나가는 일이 없게
        Object streamLock = new Object();

        for (String chapterId : HEAVY_CHAPTER_IDS) {
            Object data = chapterData.get(chapterId);
            CompletableFuture<Void> f = CompletableFuture
                    .supplyAsync(() -> llmClient.callHeavyChapter(chapterId, data, deadline), llmExecutor)
                    .thenAccept(result -> {
                        synchronized (streamLock) {
                            // 마감 후 timeout으로 이미 채워진 챕터면 버린다 — 챕터당 정확히 1건만 전송
                            if (collected.putIfAbsent(chapterId, result) != null) {
                                return;
                            }
                            // 완료되는 즉시 전송 — 12절 4번(9/29), allOf 완료를 기다리지 않는다
                            if (!clientGone.get() && !sendChapter(emitter, result)) {
                                clientGone.set(true);
                            }
                        }
                    });
            allDone.add(f);
        }

        long startedAt = System.currentTimeMillis();
        CompletableFuture<Void> all = CompletableFuture.allOf(allDone.toArray(new CompletableFuture[0]));
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

        // 마감까지 못 끝난 챕터는 timeout으로 채워서 전송 — putIfAbsent라 늦게 도착한 실제 결과와 겹쳐도
        // 챕터당 1건만 나가고, done 전에 항상 정확히 8건이 간다(프론트가 이 전제로 짜여 있음)
        List<HeavyCommentResponse.ChapterResult> finalResults = new ArrayList<>();
        synchronized (streamLock) {
            for (String chapterId : HEAVY_CHAPTER_IDS) {
                HeavyCommentResponse.ChapterResult timeout =
                        new HeavyCommentResponse.ChapterResult(chapterId, HeavyCommentResponse.STATUS_TIMEOUT, null, null);
                HeavyCommentResponse.ChapterResult existing = collected.putIfAbsent(chapterId, timeout);
                if (existing == null && !clientGone.get()) {
                    sendChapter(emitter, timeout);
                }
                finalResults.add(existing != null ? existing : timeout);
            }
            if (!clientGone.get()) {
                sendDone(emitter);
            }
        }

        if (cache != null) {
            cache.put(normalized, new HeavyCommentResponse(finalResults));
        }
        emitter.complete();
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
