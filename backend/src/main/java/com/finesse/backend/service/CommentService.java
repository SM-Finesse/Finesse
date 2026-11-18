package com.finesse.backend.service;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmLightComment;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.GlobalExceptionHandler;
import com.finesse.backend.exception.QueuedException;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TooManyTicketsException;
import com.finesse.backend.exception.UserNotFoundException;
import com.finesse.backend.queue.CommentQueue;
import com.finesse.backend.queue.TicketRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GET /api/v1/comment/{username}?scope=light|heavy 오케스트레이션.
 * Finesse-API명세서 4.2절(흐름), 4.2-1절(heavy 챕터 상세 규칙), 6장(LLM 서버 연동).
 * <p>
 * 대기열(app.queue.enabled)을 켜면 「전적 검색 대기열 문서」 4장대로 — 결과가 없을 때 LLM 예상 대기로 입장 판정(light 15초·heavy 30초),
 * 넘으면 대체 문장 대신 대기표(light는 202 stage comment, heavy는 200 스트림 안 event: queued stage heavy).
 * stats가 캐시 미스이고 수집 예상 대기가 5초를 넘으면 stage stats 대기표. 꺼져 있으면 지금 동작 그대로.
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
    // heavy 챕터 호출을 돌리는 곳 — 대기열 꺼짐이면 지금처럼 llmExecutor(고정 스레드풀).
    // 대기열 켬이면 가상 스레드: 챕터 호출이 스레드풀에서 기다리지 않고 바로 LLM 서버 대기열에 들어가야
    // 라이트·헤비가 서버 대기열 하나에서 들어온 순서대로 처리되고, 입장 판정의 LLM 예상 대기에 밀린 호출이 모두 잡힌다
    // (실제 시험 11/18 — 스레드풀에서 기다리던 호출이 예상 대기에서 빠져 heavy 7개가 몰려도 대기표 없이 마감 초과).
    private final ExecutorService chapterExecutor;
    private final LlmProperties llmProperties;
    private final EndpointProperties endpointProperties;
    private final LightCommentProperties lightCommentProperties;
    private final QueueProperties queueProperties;
    private final TicketRegistry ticketRegistry;
    private final CommentQueue commentQueue; // 대기열 꺼짐이면 null

    public CommentService(StatsService statsService, LlmClient llmClient, CacheManager cacheManager,
                           ExecutorService llmExecutor, LlmProperties llmProperties,
                           EndpointProperties endpointProperties) {
        this(statsService, llmClient, cacheManager, llmExecutor, llmProperties, endpointProperties,
                LightCommentProperties.defaults());
    }

    public CommentService(StatsService statsService, LlmClient llmClient, CacheManager cacheManager,
                           ExecutorService llmExecutor, LlmProperties llmProperties,
                           EndpointProperties endpointProperties, LightCommentProperties lightCommentProperties) {
        this(statsService, llmClient, cacheManager, llmExecutor, llmProperties, endpointProperties,
                lightCommentProperties, QueueProperties.disabled(), null);
    }

    @Autowired
    public CommentService(StatsService statsService, LlmClient llmClient, CacheManager cacheManager,
                           ExecutorService llmExecutor, LlmProperties llmProperties,
                           EndpointProperties endpointProperties, LightCommentProperties lightCommentProperties,
                           QueueProperties queueProperties, TicketRegistry ticketRegistry) {
        this.queueProperties = queueProperties;
        this.ticketRegistry = ticketRegistry;
        this.commentQueue = queueProperties.enabled() && ticketRegistry != null
                ? new CommentQueue(queueProperties, ticketRegistry, llmClient, ticketRegistry::now, true)
                : null;
        this.lightCommentProperties = lightCommentProperties;
        this.statsService = statsService;
        this.llmClient = llmClient;
        this.cacheManager = cacheManager;
        this.llmExecutor = llmExecutor;
        this.chapterExecutor = commentQueue != null ? Executors.newVirtualThreadPerTaskExecutor() : llmExecutor;
        this.llmProperties = llmProperties;
        this.endpointProperties = endpointProperties;
    }

    @PreDestroy
    void stopQueue() {
        if (commentQueue != null) {
            commentQueue.stop();
        }
    }

    public LightCommentResponse getLight(String username) {
        return getLight(username, null);
    }

    /** @param clientIp 대기표 IP 제한용(X-Real-IP) — 대기열이 꺼져 있으면 쓰지 않는다 */
    public LightCommentResponse getLight(String username, String clientIp) {
        String normalized = Usernames.normalize(username);
        // stats를 comment-light 캐시 계산 "밖에서" 먼저 받는다. stats가 새로 계산되면 comment 캐시를 연쇄 무효화하는데,
        // 캐시 계산 안에서 받으면 지금 만들고 있는 comment-light 칸을 스스로 지우게 되어 Caffeine이
        // "Recursive update"로 거부한다(stats가 캐시에 없을 때 light를 먼저 부르면 500 — 2026-10-06 발견).
        if (commentQueue == null) {
            return lightDirect(normalized, statsService.getStats(normalized, false));
        }
        // 대기열 켬 — stats 캐시도 없으면 먼저 수집 줄에 서고, 예상 대기가 5초를 넘으면 202(stage stats)가 여기서 올라간다
        StatsResponse stats = statsService.getStats(normalized, false, clientIp);
        return lightQueued(normalized, stats, clientIp);
    }

    /** 지금 동작 — 캐시에 없으면 요청 안에서 만든다(같은 유저가 동시에 오면 Caffeine이 한 번만 만든다) */
    private LightCommentResponse lightDirect(String normalized, StatsResponse stats) {
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
        if (cache == null) {
            return computeLight(stats).response();
        }
        try {
            LightResult computed = cache.get(normalized, () -> computeLight(stats));
            LightCommentResponse result = computed.response();
            if (!stats.coldStart() && (result.highlights().size() < 3 || computed.fallback())) {
                // 하이라이트가 모자라거나 대체 문장이 섞인 응답은 보여주되 캐시하지 않는다 — 10분 동안 고정되지 않고
                // 다음 요청 때 다시 시도 (heavy의 "성공 챕터만 캐시"와 같은 원칙). 콜드스타트 안내는 LLM과 무관하니 그대로 둔다.
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
     * 대기열 켬 — 「전적 검색 대기열 문서」 4.1·4.2절.
     * 미리 만든 결과(캐시·준비)가 있으면 바로, 같은 유저 코멘트가 생성 중이면 그 결과를 기다리고,
     * 없으면 LLM 예상 대기로 입장 판정 — 15초 이하면 지금처럼 요청 안에서, 넘으면 202 + 대기표(stage comment).
     */
    private LightCommentResponse lightQueued(String normalized, StatsResponse stats, String clientIp) {
        LightCommentResponse ready = readyLight(normalized);
        if (ready != null) {
            return ready;
        }
        if (stats.coldStart()) {
            return lightDirect(normalized, stats); // 콜드스타트 안내는 LLM을 부르지 않는다 — 줄 설 이유가 없다
        }
        CommentQueue.Admission admission = commentQueue.admit(CommentQueue.Kind.LIGHT, normalized, true,
                () -> generateLight(normalized, stats), clientIp);
        switch (admission) {
            case CommentQueue.Admission.Inline inline -> {
                try {
                    return lightDirect(normalized, stats);
                } finally {
                    commentQueue.inlineFinished();
                }
            }
            case CommentQueue.Admission.Join join -> {
                // 같은 유저 코멘트가 이미 생성 중(미리 만들기 등) — 새로 만들지 않고 그 결과를 기다린다
                try {
                    join.job().done().get(endpointProperties.lightSeconds() + 5L, TimeUnit.SECONDS);
                } catch (TimeoutException | ExecutionException e) {
                    log.warn("light 생성 합류 대기 실패 — 요청 안에서 다시 만듦: {}", e.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                LightCommentResponse joined = readyLight(normalized);
                return joined != null ? joined : lightDirect(normalized, stats);
            }
            case CommentQueue.Admission.Queued queued -> {
                QueueTicketResponse body = ticketRegistry.publish(queued.ticket());
                log.info("light 대기표 발급 ticket={} (position {}, 예상 {}s)",
                        body.ticketId(), body.position(), body.estimatedWaitSeconds());
                throw new QueuedException(body);
            }
        }
    }

    /**
     * 대기표·미리 만들기로 만든 결과 중 comment-light·comment-heavy에 넣지 않는 것(대체 문장·실패 챕터가 섞임) —
     * 같은 유저 대기표가 여러 장이어도 done 뒤 다시 부르면 모두 같은 결과를 받도록(3.6절), 끝난 대기표를 지우는 시간(2분) 동안만 둔다.
     * 그 뒤에는 지금처럼 다시 만든다(대체 문장을 10분 동안 고정하지 않는 원칙).
     */
    record PreparedLight(LightResult result, long preparedNanos) {
    }

    record PreparedHeavy(List<HeavyCommentResponse.ChapterResult> results, long preparedNanos) {
    }

    private boolean fresh(long preparedNanos) {
        return ticketRegistry.now() - preparedNanos <= TimeUnit.SECONDS.toNanos(queueProperties.doneRetentionSeconds());
    }

    /** 미리 만든 라이트 결과 — comment-light 캐시, 없으면 준비 칸(2분 안) */
    private LightCommentResponse readyLight(String normalized) {
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
        LightResult c = cache != null ? cache.get(normalized, LightResult.class) : null;
        if (c != null) {
            return c.response();
        }
        Cache prepared = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_PREPARED_CACHE);
        PreparedLight p = prepared != null ? prepared.get(normalized, PreparedLight.class) : null;
        if (p == null) {
            return null;
        }
        if (!fresh(p.preparedNanos())) {
            prepared.evict(normalized);
            return null;
        }
        return p.result().response();
    }

    /** 미리 만든 헤비 결과(8챕터, 실패 챕터 포함) — 준비 칸(2분 안) */
    private List<HeavyCommentResponse.ChapterResult> readyHeavy(String normalized) {
        Cache prepared = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_PREPARED_CACHE);
        PreparedHeavy p = prepared != null ? prepared.get(normalized, PreparedHeavy.class) : null;
        if (p == null) {
            return null;
        }
        if (!fresh(p.preparedNanos())) {
            prepared.evict(normalized);
            return null;
        }
        return p.results();
    }

    /** 대기표 작업의 결과가 지금 stats와 맞는지 — 그사이 stats가 새로 계산됐으면 버린다 */
    private boolean matchesCurrentStats(String normalized, StatsResponse stats) {
        Cache statsCache = cacheManager.getCache(CacheConfig.STATS_CACHE);
        StatsResponse current = statsCache != null ? statsCache.get(normalized, StatsResponse.class) : null;
        return current == null || current.updatedAt() == null || current.updatedAt().equals(stats.updatedAt());
    }

    /**
     * 대기표·미리 만들기 작업 — 차례가 오면 지금과 같은 방식으로 만든다(light 40초 마감은 이때부터, 대기표로 기다린 시간은 넣지 않음).
     * 지금처럼 캐시할 수 있는 결과는 comment-light에, 대체 문장이 섞였거나 하이라이트가 모자란 결과는 준비 칸에 넣어
     * 대기표 done 뒤 다시 부를 때 LLM을 또 부르지 않게 한다. 그사이 stats가 새로 계산됐으면 버린다.
     */
    private void generateLight(String normalized, StatsResponse stats) {
        LightResult result = computeLight(stats);
        if (!matchesCurrentStats(normalized, stats)) {
            log.info("light 대기표 작업 결과 버림 — 그사이 stats가 새로 계산됨");
            return;
        }
        // 캐시 기준은 getLight와 같다 — 하이라이트 3개 이상이고 대체 문장이 없을 때만 comment-light(10분)
        boolean cacheable = stats.coldStart() || (result.response().highlights().size() >= 3 && !result.fallback());
        if (cacheable) {
            Cache cache = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
            if (cache != null) {
                cache.put(normalized, result);
            }
        } else {
            Cache prepared = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_PREPARED_CACHE);
            if (prepared != null) {
                prepared.put(normalized, new PreparedLight(result, ticketRegistry.now()));
            }
        }
    }

    /** 대기열로 수집이 끝나자마자 라이트 코멘트를 같은 순서로 미리 만든다(4.1절) */
    @EventListener
    public void onStatsCollected(StatsCollectedEvent event) {
        if (commentQueue == null || !queueProperties.prefetchLight()) {
            return;
        }
        StatsResponse stats = event.stats();
        // 미리 만들기는 대기표로 수집한 유저만 — 요청 안에서 끝난 사람은 바로 200을 받고 이어서 light를 부를 때 입장 판정(4.1절)
        if (!event.ticketed() || stats.coldStart() || readyLight(event.username()) != null) {
            return;
        }
        commentQueue.prefetch(CommentQueue.Kind.LIGHT, event.username(), () -> generateLight(event.username(), stats));
    }

    /**
     * scope=heavy — 12절 4번(2026-09-29 확정): 챕터가 완료되는 대로 SSE로 개별 전달한다(순차/스트리밍).
     * 전체를 모아 한 번에 응답하던 allOf 방식은 폐기 — 사용자가 채워진 부분부터 바로 볼 수 있게 하기 위함.
     * 캐시는 성공(ok)한 챕터만 챕터 단위로 둔다 — 캐시에 있는 챕터는 즉시 보내고, 없는 챕터만 LLM에 돌린다.
     */
    public SseEmitter getHeavyStream(String username) {
        return getHeavyStream(username, null);
    }

    /** @param clientIp 대기표 IP 제한용(X-Real-IP) — 대기열이 꺼져 있으면 쓰지 않는다 */
    public SseEmitter getHeavyStream(String username, String clientIp) {
        String normalized = Usernames.normalize(username);
        // SseEmitter 자체 타임아웃은 heavy 최악 상한(단일 서버 120s)보다 넉넉히 잡는다 — 실제 컷오프는
        // 아래 heavyTimeoutSeconds(엔드포인트 데드라인)가 담당하므로 여기선 연결이 일찍 끊기지만 않으면 됨.
        SseEmitter emitter = new SseEmitter(150_000L);
        sendStart(emitter);
        llmExecutor.execute(() -> streamHeavy(normalized, emitter, clientIp));
        return emitter;
    }

    /**
     * 스트림을 열자마자 보내는 이벤트 — EventSource는 ": ping" 주석 줄을 화면 코드에 넘기지 않아서,
     * stats 수집(최대 20초)과 첫 챕터 LLM 호출을 기다리는 동안 프론트가 "첫 이벤트 없음"으로 끊지 않게 한다
     * (QA 검수요청서 R-05, 10/12). 챕터 결과가 아니므로 기존 chapter·done·error 처리에는 영향이 없다.
     */
    private void sendStart(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("start").data(new HeavyStreamStart(HEAVY_CHAPTER_IDS.size())));
        } catch (IOException e) {
            // 아직 응답 전이라 실패할 일은 거의 없다 — 실패해도 챕터 전송은 그대로 진행
        }
    }

    /** start 이벤트 data — 이번 스트림에서 올 챕터 수 */
    private record HeavyStreamStart(int totalChapters) {
    }

    /**
     * stats를 먼저 받고 나서 heavy 캐시를 읽는다 — stats가 새로 계산되면 comment 캐시가 연쇄 무효화되므로,
     * 순서가 반대면 새 stats와 맞지 않는 옛 챕터 코멘트를 보낼 수 있다.
     */
    private void streamHeavy(String normalized, SseEmitter emitter, String clientIp) {
        StatsResponse stats;
        try {
            stats = commentQueue == null
                    ? statsService.getStats(normalized, false)
                    : statsService.getStats(normalized, false, clientIp);
        } catch (QueuedException queued) {
            // stats 캐시 미스이고 수집 예상 대기가 5초를 넘음 — 200 스트림 안에서 대기표(stage stats)를 주고 닫는다(4.3절, Q22)
            sendQueued(emitter, queued.ticket());
            return;
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
        List<HeavyCommentResponse.ChapterResult> prepared = commentQueue == null ? null : readyHeavy(normalized);
        if (prepared != null) {
            // 대기표 done 뒤 다시 연 스트림 — 기다리는 동안 만든 8챕터를 바로 보낸다(4.3절, 실패 챕터 포함)
            streamAndComplete(emitter, prepared);
            return;
        }
        if (commentQueue == null || stats.coldStart()) {
            computeHeavyStreaming(normalized, emitter, cache, cachedOk, stats);
            return;
        }
        // 대기열 켬 — LLM 예상 대기 30초 이하면 지금처럼 SSE로, 넘으면 event: queued(stage heavy) 1건 후 닫는다(4.3절).
        // 브라우저 EventSource는 200이 아닌 응답(202 등)을 읽지 못해 HTTP 202를 쓰지 않는다
        CommentQueue.Admission admission;
        try {
            admission = commentQueue.admit(CommentQueue.Kind.HEAVY, normalized, false,
                    () -> generateHeavyInBackground(normalized, stats), clientIp);
        } catch (TooManyTicketsException e) {
            sendStatsFailure(emitter, e);
            return;
        }
        switch (admission) {
            case CommentQueue.Admission.Inline inline -> {
                try {
                    computeHeavyStreaming(normalized, emitter, cache, cachedOk, stats)
                            .whenComplete((v, e) -> commentQueue.inlineFinished());
                } catch (RuntimeException e) {
                    commentQueue.inlineFinished();
                    throw e;
                }
            }
            case CommentQueue.Admission.Queued queued -> sendQueued(emitter, ticketRegistry.publish(queued.ticket()));
            case CommentQueue.Admission.Join join -> // heavy는 생성 중에 합류하지 않는다(waitIfRunning=false) — 오지 않음
                    computeHeavyStreaming(normalized, emitter, cache, cachedOk, stats);
        }
    }

    /**
     * 대기표 작업 — 차례가 오면 스트림 없이 8챕터를 만든다(heavy 60초 마감은 이때부터). 성공 챕터는 지금처럼 헤비 캐시에,
     * 실패·대체 문장 챕터가 섞였으면 8챕터 전체를 준비 칸에도 넣어 done 뒤 다시 열면 바로 받게 한다(4.3절).
     */
    private void generateHeavyInBackground(String normalized, StatsResponse stats) {
        Cache cache = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_CACHE);
        HeavyChapterCache cached = cache != null ? cache.get(normalized, HeavyChapterCache.class) : null;
        Map<String, HeavyCommentResponse.ChapterResult> cachedOk = cached != null ? cached.ok() : Map.of();
        if (cachedOk.size() == HEAVY_CHAPTER_IDS.size()) {
            return;
        }
        List<HeavyCommentResponse.ChapterResult> results = computeHeavyStreaming(normalized, null, cache, cachedOk, stats).join();
        if (!matchesCurrentStats(normalized, stats)) {
            log.info("heavy 대기표 작업 결과 버림 — 그사이 stats가 새로 계산됨");
            return;
        }
        boolean allCacheable = results.stream()
                .allMatch(r -> HeavyCommentResponse.STATUS_OK.equals(r.status()) && !isLlmFallback(r));
        Cache prepared = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_PREPARED_CACHE);
        if (!allCacheable && prepared != null) {
            prepared.put(normalized, new PreparedHeavy(List.copyOf(results), ticketRegistry.now()));
        }
    }

    /**
     * event: queued / data: 대기표(202 본문과 같은 JSON) 1건 후 스트림 종료. 프론트는 받자마자 close()한다 —
     * 닫지 않으면 EventSource가 자동 재연결해 대기표를 또 받는다(5.4절).
     */
    private void sendQueued(SseEmitter emitter, QueueTicketResponse ticket) {
        log.info("heavy 스트림 대기표 ticket={} (stage {}, position {}, 예상 {}s)",
                ticket.ticketId(), ticket.stage(), ticket.position(), ticket.estimatedWaitSeconds());
        try {
            emitter.send(SseEmitter.event().name("queued").data(ticket));
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
        emitter.complete();
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
            case UserNotFoundException notFound -> new ApiErrorResponse("USER_NOT_FOUND", GlobalExceptionHandler.USER_NOT_FOUND_MESSAGE);
            case ServerBusyException busy -> new ApiErrorResponse("SERVER_BUSY",
                    GlobalExceptionHandler.SERVER_BUSY_MESSAGE, busy.retryAfterSeconds());
            case TooManyTicketsException tooMany -> new ApiErrorResponse("TOO_MANY_TICKETS", TooManyTicketsException.MESSAGE);
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

    /**
     * comment-light 캐시 값 — fallback은 응답에 넣지 않는 내부 표시: 대체 문장(LLM 서버 대체 문장·백엔드 문장 틀)이
     * 하나라도 섞였으면 true라 캐시하지 않는다(heavy fallback 챕터와 같은 원칙).
     */
    record LightResult(LightCommentResponse response, boolean fallback) {
    }

    private LightResult computeLight(StatsResponse stats) {
        if (stats.coldStart()) {
            // FR-02/기능 명세서 3.3절 — 콜드스타트는 LLM 미호출, 즉시 안내 (재요청 대상 아님)
            return new LightResult(new LightCommentResponse("최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.", List.of()), false);
        }
        // light 엔드포인트 상한 — 타임아웃 기준 문서(23번) 9절, stats 완료 이후부터 기산
        long deadline = System.nanoTime() + endpointProperties.lightSeconds() * 1_000_000_000L;
        List<LlmLightComment.Item> items = lightCommentProperties.enabled() ? lightCommentItems(stats) : List.of();
        if (!items.isEmpty()) {
            return computeLightComments(items, deadline);
        }
        // 예전 흐름(LLM이 11개 중에서 직접 고름) — 설정으로 껐거나 calc가 고른 지표가 없을 때만. 한 요청 안에서 섞지 않는다
        LlmLightRequest request = LlmLightRequest.from(stats);
        return new LightResult(llmClient.callLight(request, deadline), false);
    }

    /** light-v5.2 — calc가 고른 지표마다 개별 코멘트, 묶어서 기본 코멘트 (LLM 연동 명세 light-v5.2, 10/21) */
    private LightResult computeLightComments(List<LlmLightComment.Item> items, long deadline) {
        long startedAt = System.currentTimeMillis();
        LlmClient.LightCommentOutcome out = llmClient.callLightComments(items, deadline);
        log.info("light 코멘트 처리 종료: {}ms (지표 {}개, 대체 문장 {}/{} — 그중 백엔드 문장 틀 {}, 재요청 {}, version {})",
                System.currentTimeMillis() - startedAt, items.size(), out.fallbacks(), items.size() + 1,
                out.templates(), out.attemptCount(), out.version());
        return new LightResult(new LightCommentResponse(out.lightSummary(), out.highlights(), out.attemptCount()),
                out.fallbacks() > 0);
    }

    /**
     * calc 판정·선정 결과(highlight_judgement.picks, 0~3개)를 LLM 요청으로 바꾼다.
     * 영역은 high·normal·low → "높음"·"보통"·"낮음", 값은 화면과 같은 원값(delta_metrics) — delta_comeback도 축소값이 아니라 원값.
     * 영역이 none이거나 원값이 없는 지표는 뺀다(LLM 서버가 422로 거절하므로).
     */
    static List<LlmLightComment.Item> lightCommentItems(StatsResponse stats) {
        StatsResponse.HighlightJudgement judgement = stats.highlightJudgement();
        if (judgement == null || judgement.picks() == null) {
            return List.of();
        }
        List<LlmLightComment.Item> items = new ArrayList<>();
        for (StatsResponse.JudgedMetric pick : judgement.picks()) {
            String zone = switch (pick.zone() == null ? "" : pick.zone()) {
                case "high" -> "높음";
                case "normal" -> "보통";
                case "low" -> "낮음";
                default -> null;
            };
            Double value = displayValue(stats.deltaMetrics(), pick.key());
            if (zone != null && value != null && Double.isFinite(value)
                    && items.stream().noneMatch(i -> i.stat().equals(pick.key()))) {
                items.add(new LlmLightComment.Item(pick.key(), value, zone));
            }
        }
        return items.size() > 3 ? List.copyOf(items.subList(0, 3)) : List.copyOf(items);
    }

    /** 화면(delta_metrics)에 보이는 원값 — 후보 11개 키 → stats 필드 */
    static Double displayValue(StatsResponse.DeltaMetrics d, String key) {
        if (d == null) {
            return null;
        }
        StatsResponse.PlaystyleRelative p = d.playstyleRelative();
        return switch (key) {
            case "delta_opener" -> p == null ? null : p.deltaOpener();
            case "delta_plonk" -> p == null ? null : p.deltaPlonk();
            case "delta_stride" -> p == null ? null : p.deltaStride();
            case "delta_inf_ds" -> p == null ? null : p.deltaInfDs();
            case "delta_app" -> d.attack() == null ? null : d.attack().deltaApp();
            case "delta_weighted_app" -> d.attack() == null ? null : d.attack().deltaWeightedApp();
            case "delta_vs_apm" -> d.defense() == null ? null : d.defense().deltaVsApm();
            case "delta_cheese_index" -> d.defense() == null ? null : d.defense().deltaCheeseIndex();
            case "strength_split" -> d.strengthSplit();
            case "delta_comeback" -> d.deltaComeback();
            case "session_vs_slope" -> d.sessionVsSlope();
            default -> null;
        };
    }

    /**
     * 8챕터를 각각 dispatch하고, 챕터 하나가 끝날 때마다(thenAccept) 바로 SSE로 전송한다.
     * allOf는 "다 끝났는지" 판정에만 쓰고, 전달 자체는 더 이상 allOf 완료를 기다리지 않는다.
     */
    /**
     * @param emitter null이면 스트림 없이(대기표 작업) 만들어 캐시에만 넣는다
     * @return 8챕터 처리(캐시 저장 포함)가 끝나면 8챕터 최종 결과(timeout 채움 포함)로 완료
     */
    private CompletableFuture<List<HeavyCommentResponse.ChapterResult>> computeHeavyStreaming(String normalized, SseEmitter emitter, Cache cache,
                                                          Map<String, HeavyCommentResponse.ChapterResult> cachedOk,
                                                          StatsResponse stats) {
        if (stats.coldStart()) {
            List<HeavyCommentResponse.ChapterResult> results = HEAVY_CHAPTER_IDS.stream()
                    .map(id -> new HeavyCommentResponse.ChapterResult(id, HeavyCommentResponse.STATUS_FAILED, null, 0))
                    .toList();
            if (emitter != null) {
                streamAndComplete(emitter, results);
            }
            return CompletableFuture.completedFuture(results);
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
        AtomicBoolean clientGone = new AtomicBoolean(emitter == null);
        List<CompletableFuture<Void>> allDone = new ArrayList<>();
        // 챕터 선점+전송과 마감(timeout 채우기+done)을 한 락으로 묶는다 — 선점한 챕터가 done 뒤에 나가는 일이 없게
        Object streamLock = new Object();
        if (emitter != null) {
            emitter.onError(e -> clientGone.set(true));
            emitter.onTimeout(() -> clientGone.set(true));
        }

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
        ScheduledFuture<?> heartbeat = emitter == null ? null : heartbeatScheduler.scheduleAtFixedRate(() -> {
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
                    .supplyAsync(() -> llmClient.callHeavyChapter(chapterId, data, deadline), chapterExecutor)
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
        return CompletableFuture.allOf(allDone.toArray(CompletableFuture<?>[]::new))
                .completeOnTimeout(null, heavyTimeoutSeconds, TimeUnit.SECONDS)
                .handle((ignored, error) -> {
                    if (heartbeat != null) {
                        heartbeat.cancel(false);
                    }
                    long elapsedMs = System.currentTimeMillis() - startedAt;
                    long fallbacks = collected.values().stream().filter(CommentService::isLlmFallback).count();
                    log.info("heavy 코멘트 처리 종료: {}ms (한도 {}s, 서버 {}대, 캐시 재사용 {}챕터, 대체 문장 {}챕터) ({})",
                            elapsedMs, heavyTimeoutSeconds, serverCount, cachedOk.size(), fallbacks, normalized);
                    return finishHeavyStream(normalized, emitter, cache, collected, clientGone, streamLock, elapsedMs);
                });
    }

    private List<HeavyCommentResponse.ChapterResult> finishHeavyStream(String normalized, SseEmitter emitter, Cache cache,
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
            // LLM 서버 대체 문장은 status=ok여도 저장하지 않는다 — 10분 동안 대체 문장이 고정되지 않도록 다음 요청 때 다시 시도
            if (HeavyCommentResponse.STATUS_OK.equals(r.status()) && !isLlmFallback(r)) {
                ok.put(r.chapterId(), r);
            }
        }
        if (cache != null && !ok.isEmpty()) {
            cache.put(normalized, new HeavyChapterCache(Map.copyOf(ok)));
        }
        if (emitter != null) {
            emitter.complete();
        }
        return finalResults;
    }

    /**
     * LLM 서버가 대체 문장을 보냈는지 (fallback=true). rivals는 처음부터 서버 문장을 쓰는 챕터라(LLM_HEAVY_TEMPLATE_CHAPTERS)
     * 대체 문장으로 치지 않는다 — 다시 불러도 같은 문장이므로 캐시해도 된다.
     */
    static boolean isLlmFallback(HeavyCommentResponse.ChapterResult r) {
        return r.fallback() && !"rivals".equals(r.chapterId());
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
        map.put("attack", d.attack() != null ? d.attack().withoutAverages() : Map.of()); // my_avg·opp_avg는 화면용 — LLM 입력엔 Δ만
        map.put("defense", d.defense() != null ? d.defense().withoutAverages() : Map.of());
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
