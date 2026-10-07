package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchSeriesStats;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import com.finesse.backend.calc.domain.RivalryStats;
import com.finesse.backend.calc.domain.StatResult;
import com.finesse.backend.calc.service.AnalysisMeta;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.StatsLoadProperties;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GET /api/v1/stats/{username} 오케스트레이션 — Finesse-API명세서 4.1절 처리 흐름 그대로.
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private static final int RIVAL_PAGE_SIZE = 20;

    private final StatCalculatorFacade statCalculatorFacade;
    private final TetrioClient tetrioClient;
    private final CacheManager cacheManager;
    private final EndpointProperties endpointProperties;
    private final StatsLoadProperties statsLoadProperties;
    private final ExecutorService statsExecutor;
    private final Semaphore collectionSlots;
    private final AtomicInteger waitingForSlot = new AtomicInteger();

    public StatsService(StatCalculatorFacade statCalculatorFacade, TetrioClient tetrioClient, CacheManager cacheManager,
                         EndpointProperties endpointProperties, StatsLoadProperties statsLoadProperties,
                         @Qualifier("statsExecutor") ExecutorService statsExecutor) {
        this.statCalculatorFacade = statCalculatorFacade;
        this.tetrioClient = tetrioClient;
        this.cacheManager = cacheManager;
        this.endpointProperties = endpointProperties;
        this.statsLoadProperties = statsLoadProperties;
        this.statsExecutor = statsExecutor;
        // 공정(fair) — 기다리는 요청은 도착 순서대로 자리를 얻는다
        this.collectionSlots = new Semaphore(statsLoadProperties.maxConcurrentCollections(), true);
    }

    public StatsResponse getStats(String username, boolean refresh) {
        String normalized = Usernames.normalize(username); // 1번 — 소문자 정규화 + 형식 검사(틀리면 400)
        Cache cache = cacheManager.getCache(CacheConfig.STATS_CACHE);

        if (refresh && cache != null) {
            cache.evict(normalized);
        }
        if (cache == null) {
            return compute(normalized);
        }
        // comment(light/heavy)는 stats보다 나중에 채워지므로 각자 독립된 TTL을 두면
        // "stats는 만료됐는데 comment는 아직 살아있는" 불일치가 생긴다 (API명세서 5절, 캐시 만료 기준).
        // stats가 새로 계산되는 시점(자연 만료 후 재계산이든 refresh=true 강제 갱신이든)마다
        // comment 캐시도 함께 폐기해서, 캐시 만료/갱신 기준을 stats 하나로 통일한다.
        Callable<StatsResponse> loader = () -> {
            StatsResponse fresh = compute(normalized);
            evictCommentCaches(normalized);
            return fresh;
        };
        try {
            return cache.get(normalized, loader);
        } catch (Cache.ValueRetrievalException e) {
            // 원인 예외(UserNotFoundException/TetrioApiException)를 그대로 올려서
            // GlobalExceptionHandler가 정상적으로 매핑하게 한다.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private void evictCommentCaches(String normalized) {
        Cache light = cacheManager.getCache(CacheConfig.COMMENT_LIGHT_CACHE);
        if (light != null) {
            light.evict(normalized);
        }
        Cache heavy = cacheManager.getCache(CacheConfig.COMMENT_HEAVY_CACHE);
        if (heavy != null) {
            heavy.evict(normalized);
        }
    }

    // stats 엔드포인트 상한(타임아웃 기준 문서 23번 9절, EndpointProperties.statsSeconds) 강제 —
    // TETR.IO 호출은 블로킹이라 별도 스레드에서 실행하고 Future.get(timeout)으로 마감을 건다.
    // 캐시 미스 수집은 동시에 maxConcurrentCollections건까지만 — 자리가 없으면 정해진 시간까지 차례를 기다리고,
    // 그래도 안 되면 503 BUSY(23번 5.4절). 기다린 시간도 stats 마감 안에서 쓴다.
    private StatsResponse compute(String normalized) {
        long startedAt = System.nanoTime();
        if (!acquireCollectionSlot(normalized)) {
            throw new ServerBusyException("stats 수집 동시 처리 상한 초과: " + normalized,
                    statsLoadProperties.busyRetryAfterSeconds());
        }
        // 자리는 수집이 "실제로 끝났을 때" 반납한다 — 마감(20초)으로 502를 먼저 돌려줘도 calc 안의 수집은
        // 계속 TETR.IO를 부를 수 있으므로, 그 사이에 새 수집을 받으면 부하를 과소 계산하게 된다.
        FutureTask<StatsResponse> future = new FutureTask<>(() -> computeInternal(normalized));
        try {
            statsExecutor.execute(() -> {
                try {
                    future.run();
                } finally {
                    collectionSlots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            collectionSlots.release();
            throw new TetrioApiException("stats 실행 거부: " + normalized, e);
        }
        try {
            long remainingNanos = TimeUnit.SECONDS.toNanos(endpointProperties.statsSeconds())
                    - (System.nanoTime() - startedAt);
            return future.get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TetrioApiException(
                    "stats 엔드포인트 타임아웃(" + endpointProperties.statsSeconds() + "s) 초과: " + normalized, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new TetrioApiException("stats 계산 실패: " + normalized, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TetrioApiException("stats 계산 중단됨: " + normalized, e);
        }
    }

    /**
     * 수집 자리 얻기 — 비어 있으면 바로, 없으면 maxQueueWaitSeconds까지 도착 순서대로 기다린다(공정 세마포어).
     * 이미 maxWaiting명이 기다리고 있으면 기다려도 그 시간 안에 차례가 오기 어려우므로 기다리지 않고 거절한다
     * (23번 5.4절 "대기가 6초를 넘을 요청은 대기열에 넣지 않고 503"). 기다리는 동안 요청 스레드를 붙잡으므로
     * 대기 인원을 작게 둔다.
     */
    private boolean acquireCollectionSlot(String normalized) {
        try {
            // tryAcquire()는 공정성을 무시하고 끼어들기 때문에, 기다리는 요청이 있으면 그 뒤에 서도록 0초 대기로 시도
            if (collectionSlots.tryAcquire(0, TimeUnit.SECONDS)) {
                return true;
            }
            int maxWait = statsLoadProperties.maxQueueWaitSeconds();
            if (maxWait <= 0 || waitingForSlot.incrementAndGet() > statsLoadProperties.maxWaiting()) {
                if (maxWait > 0) {
                    waitingForSlot.decrementAndGet();
                }
                log.warn("stats 수집 자리 없음, 대기열도 참({}명) — 503 BUSY: {}", waitingForSlot.get(), normalized);
                return false;
            }
            try {
                long waitStart = System.nanoTime();
                boolean acquired = collectionSlots.tryAcquire(maxWait, TimeUnit.SECONDS);
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart);
                if (acquired) {
                    log.info("stats 수집 자리 대기 {}ms 후 시작: {}", waitedMs, normalized);
                } else {
                    log.warn("stats 수집 자리 {}초 대기 후에도 없음 — 503 BUSY: {}", maxWait, normalized);
                }
                return acquired;
            } finally {
                waitingForSlot.decrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 라이트뷰 1차 병합(2026-10-01) — 닉네임을 data-eng calc 모듈(StatCalculatorFacade)에 넘기고,
     * TETR.IO 호출·수집·정제·콜드스타트 판정·계산 결과를 받아 API 응답(4.1절)으로 옮긴다.
     * 백엔드는 calc의 공개 타입만 쓴다(ArchitectureTest "백엔드는_calc의_공개_타입만_사용한다").
     */
    private StatsResponse computeInternal(String normalized) {
        AnalysisOutcome outcome = statCalculatorFacade.analyze(normalized);
        // AnalysisOutcome은 sealed — 새 분기가 생기면 여기서 컴파일 오류로 바로 드러난다
        return switch (outcome) {
            case AnalysisOutcome.Analyzed analyzed -> analyzedResponse(normalized, analyzed);
            case AnalysisOutcome.ColdStartBypass cold -> coldStartResponse(normalized, cold);
            case AnalysisOutcome.UserNotFound notFound -> throw new UserNotFoundException(normalized);
            case AnalysisOutcome.CollectionFailed failed ->
                    throw new TetrioApiException("TETR.IO 수집 실패(" + failed.status() + "): " + normalized, null);
        };
    }

    private StatsResponse coldStartResponse(String normalized, AnalysisOutcome.ColdStartBypass cold) {
        // 10/2 확정: 1~9판은 승률·승패 칸만 — calc가 있는 경기만큼 계산한 승패를 준다.
        // 계산할 경기가 없으면(0판) recentWinLoss가 null이고, win_rate는 0.0이 아니라 생략 — 프론트는 "—"로 표시.
        RecentWinLossStats winLoss = cold.recentWinLoss();
        StatsResponse.FixedMetrics fixed = winLoss == null
                ? new StatsResponse.FixedMetrics(null, List.of(), List.of())
                : new StatsResponse.FixedMetrics(winLoss.overallWinRate(), List.of(), recentForm(winLoss));
        return new StatsResponse(normalized, true, cold.availableMatches(), Instant.now(),
                profile(normalized, cold.summary()), fixed, null,
                new StatsResponse.RoundCurves(List.of(), List.of()),
                new StatsResponse.Rivals(List.of(), 1, RIVAL_PAGE_SIZE, 0),
                Map.of("note", "콜드스타트 — 챕터 데이터 없음", "cold_start_reason", cold.reason().name()));
    }

    private StatsResponse analyzedResponse(String normalized, AnalysisOutcome.Analyzed analyzed) {
        StatResult r = analyzed.result();
        AnalysisMeta meta = analyzed.meta();
        RecentWinLossStats winLoss = r.recentWinLoss();
        DeltaStats delta = r.delta();
        HighlightStats highlight = r.highlight();

        MatchSeriesStats series = r.series();
        StatsResponse.FixedMetrics fixed = new StatsResponse.FixedMetrics(
                winLoss.overallWinRate(), trTrend(series), recentForm(winLoss));

        // delta는 계산 가능한 매치(APM > 0, PPS ≥ 0.1)가 하나도 없으면 null — 그때 플레이스타일·공격·수비는 생략
        StatsResponse.DeltaMetrics deltaMetrics = new StatsResponse.DeltaMetrics(
                highlight.trTrendDelta(),
                delta == null ? null : new StatsResponse.PlaystyleRelative(delta.deltaOpener(), delta.deltaPlonk(),
                        delta.deltaStride(), delta.deltaInfDs()),
                delta == null ? null : new StatsResponse.Attack(delta.deltaApp(), delta.deltaWeightedApp()),
                delta == null ? null : new StatsResponse.Defense(delta.deltaVsApm(), delta.deltaCheeseIndex()),
                highlight.strengthSplit(),
                highlight.comebackRate(),
                highlight.comebackRateAgainst(),
                new StatsResponse.ComebackSamples(highlight.comebackOpportunities(), highlight.comebackWon(),
                        highlight.comebackAgainstOpportunities(), highlight.comebackAgainstAllowed()),
                highlight.sessionVsSlope());

        RivalryStats rivalry = r.rivalryStats();
        List<StatsResponse.RivalItem> rivalItems = rivalry.rivals().stream()
                .limit(RIVAL_PAGE_SIZE)
                .map(o -> new StatsResponse.RivalItem(o.maskedNickname(), o.matchCount(), o.wins(), o.losses(), null))
                .toList();

        Map<String, Object> chapters = Map.of(
                "dropped_records", meta.droppedRecords(),
                "excluded_matches", meta.excludedMatches(),
                "previous_matches", meta.previousMatches(),
                "partial", meta.partial(),
                "note", "8챕터 차트 데이터 세부 스키마는 [협의 필요]");

        return new StatsResponse(normalized, false, meta.analyzedMatches(), Instant.now(),
                profile(normalized, analyzed.summary()), fixed, deltaMetrics,
                roundCurves(series),
                new StatsResponse.Rivals(rivalItems, 1, RIVAL_PAGE_SIZE, rivalry.rivalCount()),
                chapters);
    }

    /** recentResults는 최신순 — recent_form의 "index 0이 가장 최근"과 같은 순서 */
    private static List<String> recentForm(RecentWinLossStats winLoss) {
        return winLoss.recentResults().stream()
                .map(m -> m == MatchResult.WIN ? "W" : "L")
                .toList();
    }

    /** 매치 당시 TR, 오래된 경기 → 최근 경기 순 (TR 없는 매치는 calc가 이미 뺌) */
    static List<Double> trTrend(MatchSeriesStats series) {
        if (series == null) {
            return List.of();
        }
        return series.trSeries().stream().map(MatchSeriesStats.TrPoint::tr).toList();
    }

    /**
     * 라운드 순서별 평균 PPS·VS — 배열 index 0이 1라운드.
     * PPS가 없는 라운드가 하나라도 있으면 pps는 빈 배열로 둔다 — 프론트는 pps 길이가 vs와 다르면 PPS 선을
     * 그리지 않으므로, 배열 중간에 null을 섞어 보내 화면이 깨지는 일을 막는다.
     */
    static StatsResponse.RoundCurves roundCurves(MatchSeriesStats series) {
        if (series == null) {
            return new StatsResponse.RoundCurves(List.of(), List.of());
        }
        List<MatchSeriesStats.RoundPoint> curve = series.roundCurve();
        List<Double> vs = curve.stream().map(MatchSeriesStats.RoundPoint::avgVs).toList();
        boolean ppsComplete = curve.stream().allMatch(p -> p.avgPps() != null);
        List<Double> pps = ppsComplete ? curve.stream().map(MatchSeriesStats.RoundPoint::avgPps).toList() : List.of();
        return new StatsResponse.RoundCurves(pps, vs);
    }

    /**
     * 랭크·TR·APM·PPS·VS는 calc의 UserSummary에서, 프로필 사진·XP·국가·가입일·플레이 시간·친구 수는
     * 백엔드가 /users/{username}을 직접 불러 채운다.
     * TODO(data-eng 협의): /users/{username} 호출을 calc 모듈로 옮기면 백엔드 TetrioClient를 걷어낼 수 있다
     *  (지금은 레이트리미터가 calc와 따로라 두 모듈 호출이 겹치면 초당 1회를 잠깐 넘길 수 있음).
     */
    private StatsResponse.Profile profile(String normalized, UserSummary summary) {
        TetrioClient.UserInfo user;
        try {
            user = tetrioClient.fetchUserInfo(normalized, TetrioClient.newSessionId());
        } catch (TetrioApiException e) {
            log.warn("TETR.IO 유저 정보 조회 실패 — 프로필 사진·XP·국가·가입일 생략: {}", normalized, e);
            user = TetrioClient.UserInfo.empty();
        }
        return new StatsResponse.Profile(summary.rank(), summary.tr(), summary.glicko(), summary.rd(),
                summary.apm(), summary.pps(), summary.vs(),
                avatarUrl(user), user.xp(), user.country(), user.joinedAt(),
                user.gametime(), user.friendCount());
    }

    /** TETR.IO 프로필 사진 주소 — 사진을 올린 적 없는 유저는 avatar_revision이 없어 null(응답에서 생략). */
    private static String avatarUrl(TetrioClient.UserInfo user) {
        if (user.id() == null || user.avatarRevision() == null) {
            return null;
        }
        return "https://tetr.io/user-content/avatars/" + user.id() + ".jpg?rv=" + user.avatarRevision();
    }
}
