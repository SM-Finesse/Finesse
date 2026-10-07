package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.GlobalExceptionHandler;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.UserNotFoundException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    // heavy SSE 하트비트 간격 — 이벤트가 한동안 없을 때 프록시·브라우저가 유휴 연결로 보고 끊지 않게
    private static final long HEARTBEAT_SECONDS = 10;

    // 라이벌 챕터 LLM 입력 인원 (v1.2 7.4절 제안, 백엔드 결정 10/7)
    static final int RIVALS_FOR_LLM = 5;

    // 반복 조우 상대가 없을 때 라이벌 챕터 각주 — LLM을 부르지 않고 status=ok로 보낸다 (10/7 결정).
    // 프론트는 이때도 만난 상대 목록이 있으면 그대로 그리므로, 목록 위에 붙어도 어색하지 않은 문구로 (프론트 의견 10/7)
    static final String NO_RIVALS_FOOTNOTE = "5경기 이상 만난 상대가 아직 없어 상성을 판단하기 이릅니다.";

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

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
        String normalized = Usernames.normalize(username);
        // stats를 comment-light 캐시 계산 "밖에서" 먼저 받는다. stats가 새로 계산되면 comment 캐시를 연쇄 무효화하는데,
        // 캐시 계산 안에서 받으면 지금 만들고 있는 comment-light 칸을 스스로 지우게 되어 Caffeine이
        // "Recursive update"로 거부한다(stats가 캐시에 없을 때 light를 먼저 부르면 500 — 2026-10-06 발견).
        StatsResponse stats = statsService.getStats(normalized, false);
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
        if (cache == null) {
            return computeLight(stats);
        }
        try {
            LightCommentResponse result = cache.get(normalized, () -> computeLight(stats));
            if (!stats.coldStart() && result.highlights().size() < 3) {
                // 하이라이트가 모자란 응답은 보여주되 캐시하지 않는다 — 10분 동안 고정되지 않고 다음 요청 때 다시 시도
                // (heavy의 "성공 챕터만 캐시"와 같은 원칙). 콜드스타트 안내는 LLM과 무관하니 그대로 둔다.
                cache.evict(normalized);
            }
            return result;
        } catch (Cache.ValueRetrievalException e) {
            // 원인 예외(UserNotFound·ServerBusy·TETR.IO·LLM)를 그대로 올려 GlobalExceptionHandler가 404/502/503으로
            // 매핑하게 한다 — 감싼 채로 두면 공통 500 처리에 걸린다 (StatsService.getStats와 같은 처리)
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /**
     * scope=heavy — 12절 4번(2026-09-29 확정): 챕터가 완료되는 대로 SSE로 개별 전달한다(순차/스트리밍).
     * 전체를 모아 한 번에 응답하던 allOf 방식은 폐기 — 사용자가 채워진 부분부터 바로 볼 수 있게 하기 위함.
     * 캐시는 성공(ok)한 챕터만 챕터 단위로 둔다 — 캐시에 있는 챕터는 즉시 보내고, 없는 챕터만 LLM에 돌린다.
     */
    public SseEmitter getHeavyStream(String username) {
        String normalized = Usernames.normalize(username);
        // SseEmitter 자체 타임아웃은 heavy 최악 상한(단일 서버 120s)보다 넉넉히 잡는다 — 실제 컷오프는
        // 아래 heavyTimeoutSeconds(엔드포인트 데드라인)가 담당하므로 여기선 연결이 일찍 끊기지만 않으면 됨.
        SseEmitter emitter = new SseEmitter(150_000L);
        llmExecutor.execute(() -> streamHeavy(normalized, emitter));
        return emitter;
    }

    /**
     * stats를 먼저 받고 나서 heavy 캐시를 읽는다 — stats가 새로 계산되면 comment 캐시가 연쇄 무효화되므로,
     * 순서가 반대면 새 stats와 맞지 않는 옛 챕터 코멘트를 보낼 수 있다.
     */
    private void streamHeavy(String normalized, SseEmitter emitter) {
        StatsResponse stats;
        try {
            stats = statsService.getStats(normalized, false);
        } catch (RuntimeException e) {
            // stats 실패(없는 유저·TETR.IO 장애·503 BUSY)를 잡지 않으면 스트림이 아무것도 못 받은 채
            // SseEmitter 타임아웃(150초)까지 열려 있다 — 실패 이벤트 하나 보내고 바로 닫는다.
            sendStatsFailure(emitter, e);
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_CACHE);
        HeavyChapterCache cached = cache != null ? cache.get(normalized, HeavyChapterCache.class) : null;
        Map<String, HeavyCommentResponse.ChapterResult> cachedOk = cached != null ? cached.ok() : Map.of();
        if (cachedOk.size() == HEAVY_CHAPTER_IDS.size()) {
            streamAndComplete(emitter, HEAVY_CHAPTER_IDS.stream().map(cachedOk::get).toList());
            return;
        }
        computeHeavyStreaming(normalized, emitter, cache, cachedOk, stats);
    }

    /**
     * comment-heavy 캐시 값 — status=ok인 챕터만 담는다. failed/timeout까지 통째로 넣으면 10분 동안 실패가
     * 고정되므로(새로고침해도 같은 빈 각주), 실패 챕터는 다음 요청 때 그것만 다시 시도하게 한다.
     * light의 "하이라이트 3개 미만 응답은 캐시하지 않음"과 같은 원칙.
     */
    private record HeavyChapterCache(Map<String, HeavyCommentResponse.ChapterResult> ok) {
    }

    /** done 이벤트 data — 프론트가 "다 끝났다"와 "몇 개가 실패했는지"를 한 번에 알 수 있게. */
    private record HeavyStreamDone(int completed, List<String> failedChapters, Meta meta) {
        private record Meta(long elapsedMs) {
        }
    }

    private void streamAndComplete(SseEmitter emitter, List<HeavyCommentResponse.ChapterResult> results) {
        for (HeavyCommentResponse.ChapterResult r : results) {
            if (!sendChapter(emitter, r)) {
                return;
            }
        }
        sendDone(emitter, results, 0);
        emitter.complete();
    }

    private boolean sendChapter(SseEmitter emitter, HeavyCommentResponse.ChapterResult result) {
        try {
            emitter.send(SseEmitter.event().id(result.chapterId()).name("chapter").data(result));
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
    private void sendDone(SseEmitter emitter, List<HeavyCommentResponse.ChapterResult> results, long elapsedMs) {
        List<String> failed = results.stream()
                .filter(r -> !HeavyCommentResponse.STATUS_OK.equals(r.status()))
                .map(HeavyCommentResponse.ChapterResult::chapterId)
                .toList();
        HeavyStreamDone done = new HeavyStreamDone(results.size() - failed.size(), failed,
                new HeavyStreamDone.Meta(elapsedMs));
        try {
            emitter.send(SseEmitter.event().name("done").data(done));
        } catch (IOException e) {
            // 이미 끊긴 연결 — 무시
        }
    }

    /** heavy 스트림 시작 전 stats 단계 실패 — event: error / data: {error_code, message} 후 종료. */
    static ApiErrorResponse statsFailureBody(RuntimeException e) {
        return switch (e) {
            case UserNotFoundException notFound -> new ApiErrorResponse("USER_NOT_FOUND", notFound.getMessage());
            case ServerBusyException busy -> new ApiErrorResponse("SERVER_BUSY",
                    GlobalExceptionHandler.SERVER_BUSY_MESSAGE, busy.retryAfterSeconds());
            default -> new ApiErrorResponse("TETRIO_API_UNAVAILABLE", GlobalExceptionHandler.TETRIO_UNAVAILABLE_MESSAGE);
        };
    }

    private void sendStatsFailure(SseEmitter emitter, RuntimeException e) {
        // 문구는 HTTP 오류 응답과 같은 사용자용 문구 — 내부 메시지(유저명·수집 상태 등)는 로그에만 남긴다.
        // SERVER_BUSY는 SSE에 Retry-After 헤더를 실을 수 없으니 retry_after_seconds로 함께 보낸다.
        ApiErrorResponse body = statsFailureBody(e);
        log.warn("heavy 스트림 시작 전 stats 실패({}): {}", body.errorCode(), e.getMessage());
        try {
            emitter.send(SseEmitter.event().name("error").data(body));
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
        emitter.complete();
    }

    private LightCommentResponse computeLight(StatsResponse stats) {
        if (stats.coldStart()) {
            // FR-02/기능 명세서 3.3절 — 콜드스타트는 LLM 미호출, 즉시 안내 (재요청 대상 아님)
            return new LightCommentResponse("최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.", List.of());
        }
        // light 엔드포인트 상한 — 타임아웃 기준 문서(23번) 9절, stats 완료 이후부터 기산
        long deadline = System.nanoTime() + endpointProperties.lightSeconds() * 1_000_000_000L;
        LlmLightRequest request = LlmLightRequest.from(stats);
        return llmClient.callLight(request, deadline);
    }

    /**
     * 8챕터를 각각 dispatch하고, 챕터 하나가 끝날 때마다(thenAccept) 바로 SSE로 전송한다.
     * allOf는 "다 끝났는지" 판정에만 쓰고, 전달 자체는 더 이상 allOf 완료를 기다리지 않는다.
     */
    private void computeHeavyStreaming(String normalized, SseEmitter emitter, Cache cache,
                                       Map<String, HeavyCommentResponse.ChapterResult> cachedOk,
                                       StatsResponse stats) {
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
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        // 캐시에 남아 있던 성공 챕터는 LLM을 다시 부르지 않고 바로 보낸다
        synchronized (streamLock) {
            for (String chapterId : HEAVY_CHAPTER_IDS) {
                HeavyCommentResponse.ChapterResult hit = cachedOk.get(chapterId);
                if (hit == null && "rivals".equals(chapterId) && hasNoRivals(stats)) {
                    // 반복 조우 상대가 없으면 LLM을 부르지 않고 고정 문구를 ok로 (정상적인 빈 상태라 failed로 보내지 않는다)
                    hit = new HeavyCommentResponse.ChapterResult(chapterId, HeavyCommentResponse.STATUS_OK, NO_RIVALS_FOOTNOTE, 0);
                }
                if (hit != null) {
                    collected.put(chapterId, hit);
                    if (!clientGone.get() && !sendChapter(emitter, hit)) {
                        clientGone.set(true);
                    }
                }
            }
        }

        // 챕터가 늦게 올 때 프록시·브라우저가 유휴 스트림을 끊지 않도록 주석 줄(": ping")을 주기적으로 보낸다
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            synchronized (streamLock) {
                if (clientGone.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    clientGone.set(true);
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        for (String chapterId : HEAVY_CHAPTER_IDS) {
            if (collected.containsKey(chapterId)) {
                continue;
            }
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
        // 마감까지 블로킹으로 기다리지 않는다 — 기다리는 동안 llmExecutor 스레드를 붙잡으면 동시 heavy 요청이
        // 늘 때 챕터 호출이 돌 스레드가 모자라진다. 전체 상한이 되면 completeOnTimeout이 마감 처리를 깨운다
        // (엔드포인트 타임아웃이 개별 챕터 재시도보다 우선, 4.2-1절).
        CompletableFuture.allOf(allDone.toArray(CompletableFuture<?>[]::new))
                .completeOnTimeout(null, heavyTimeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    heartbeat.cancel(false);
                    long elapsedMs = System.currentTimeMillis() - startedAt;
                    log.info("heavy 코멘트 처리 종료: {}ms (한도 {}s, 서버 {}대, 캐시 재사용 {}챕터) ({})",
                            elapsedMs, heavyTimeoutSeconds, serverCount, cachedOk.size(), normalized);
                    finishHeavyStream(normalized, emitter, cache, collected, clientGone, streamLock, elapsedMs);
                });
    }

    private void finishHeavyStream(String normalized, SseEmitter emitter, Cache cache,
                                   Map<String, HeavyCommentResponse.ChapterResult> collected,
                                   AtomicBoolean clientGone, Object streamLock, long elapsedMs) {
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
                sendDone(emitter, finalResults, elapsedMs);
            }
        }

        Map<String, HeavyCommentResponse.ChapterResult> ok = new LinkedHashMap<>();
        for (HeavyCommentResponse.ChapterResult r : finalResults) {
            if (HeavyCommentResponse.STATUS_OK.equals(r.status())) {
                ok.put(r.chapterId(), r);
            }
        }
        if (cache != null && !ok.isEmpty()) {
            cache.put(normalized, new HeavyChapterCache(Map.copyOf(ok)));
        }
        emitter.complete();
    }

    /**
     * 챕터별 LLM 입력 data — LLM/AI 파트 설계 v1.2 5.3절(data는 object)·7.3절 표(챕터별 값)·7.4절(라이벌).
     * 값이 없는 필드는 키째 뺀다(light와 같은 규칙). 콜드스타트는 여기까지 오지 않는다.
     */
    static Map<String, Object> buildChapterData(StatsResponse stats) {
        StatsResponse.DeltaMetrics d = stats.deltaMetrics();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tr_trend", fields("tr_trend_delta", d.trTrendDelta()));
        map.put("playstyle", d.playstyleRelative() != null ? d.playstyleRelative() : Map.of());
        map.put("attack", d.attack() != null ? d.attack() : Map.of());
        map.put("defense", d.defense() != null ? d.defense() : Map.of());
        map.put("strength_split", fields("strength_split", d.strengthSplit()));
        Map<String, Object> comeback = fields("comeback_rate", d.comebackRate(),
                "comeback_rate_against", d.comebackRateAgainst());
        StatsResponse.ComebackSamples samples = d.comebackSamples();
        if (samples != null) {
            // 표본 수 — LLM이 "몇 번 중 몇 번"까지 쓸 수 있게 (v1.2 7.3절 "역전 표본 수", 백엔드 결정 10/7)
            comeback.put("comeback_opportunities", samples.comebackOpportunities());
            comeback.put("comeback_won", samples.comebackWon());
            comeback.put("comeback_against_opportunities", samples.comebackAgainstOpportunities());
            comeback.put("comeback_against_allowed", samples.comebackAgainstAllowed());
        }
        map.put("comeback_rate", comeback);
        map.put("session_vs_slope", fields("session_vs_slope", d.sessionVsSlope()));
        // 라이벌은 조우 횟수 순 상위 RIVALS_FOR_LLM명만, last_match_at 없이 (v1.2 7.4절 — 백엔드 결정 10/7)
        List<RivalInput> rivals = stats.rivals() == null ? List.of() : stats.rivals().items().stream()
                .limit(RIVALS_FOR_LLM)
                .map(r -> new RivalInput(r.nicknameMasked(), r.matches(), r.wins(), r.losses()))
                .toList();
        map.put("rivals", Map.of("rivals", rivals));
        return map;
    }

    /** 라이벌 챕터 LLM 입력 1명 — 필드명은 stats 응답 rivals.items[]와 같게 (v1.2 7.4절) */
    record RivalInput(String nicknameMasked, int matches, int wins, int losses) {
    }

    /** 이름·값 쌍에서 값이 있는 것만 담는다 (Map의 null 값은 Jackson이 생략해 주지 않으므로 직접 거른다) */
    private static Map<String, Object> fields(Object... nameValuePairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (nameValuePairs[i + 1] != null) {
                m.put((String) nameValuePairs[i], nameValuePairs[i + 1]);
            }
        }
        return m;
    }

    /** 반복 조우(5경기 이상) 상대가 없으면 라이벌 챕터는 LLM 없이 이 문구를 ok로 보낸다 — failed면 화면에 오류처럼 보임 */
    private static boolean hasNoRivals(StatsResponse stats) {
        return stats.rivals() == null || stats.rivals().items().isEmpty();
    }
}
