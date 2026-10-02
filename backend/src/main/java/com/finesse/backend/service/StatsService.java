package com.finesse.backend.service;

import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchResult;
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

    public StatsService(StatCalculatorFacade statCalculatorFacade, TetrioClient tetrioClient, CacheManager cacheManager,
                         EndpointProperties endpointProperties, StatsLoadProperties statsLoadProperties,
                         @Qualifier("statsExecutor") ExecutorService statsExecutor) {
        this.statCalculatorFacade = statCalculatorFacade;
        this.tetrioClient = tetrioClient;
        this.cacheManager = cacheManager;
        this.endpointProperties = endpointProperties;
        this.statsLoadProperties = statsLoadProperties;
        this.statsExecutor = statsExecutor;
        this.collectionSlots = new Semaphore(statsLoadProperties.maxConcurrentCollections());
    }

    public StatsResponse getStats(String username, boolean refresh) {
        String normalized = username.toLowerCase(); // 1번 — TETR.IO API는 소문자가 아니면 404를 반환함 (실측 확인)
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
    // 캐시 미스 수집은 동시에 maxConcurrentCollections건까지만 — 넘치면 줄 세우지 않고 바로 503 BUSY(23번 5.4절).
    private StatsResponse compute(String normalized) {
        if (!collectionSlots.tryAcquire()) {
            log.warn("stats 수집 동시 처리 상한({}건) 초과 — 503 BUSY: {}",
                    statsLoadProperties.maxConcurrentCollections(), normalized);
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
            return future.get(endpointProperties.statsSeconds(), TimeUnit.SECONDS);
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
        // TODO(data-eng 진행 중): 콜드스타트 결과에는 아직 승패 기록이 없어 win_rate·recent_form을 채울 수 없다.
        //  ColdStartBypass에 RecentWinLossStats가 들어오면 여기서 채운다(10/2 확정: 1~9판은 승률·승패 칸만).
        //  그 전까지 win_rate는 0.0이 아니라 null(생략) — 프론트는 "—"로 표시(frontend 1dbd9bd).
        StatsResponse.FixedMetrics fixed = new StatsResponse.FixedMetrics(null, List.of(), List.of());
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

        // recentResults는 최신순 — recent_form의 "index 0이 가장 최근"과 같은 순서
        List<String> recentForm = winLoss.recentResults().stream()
                .map(m -> m == MatchResult.WIN ? "W" : "L")
                .toList();
        // TODO(data-eng 협의): 경기별 TR 시계열(tr_trend)·라운드별 곡선(round_curves)은 calc 결과에 없어 빈 값.
        StatsResponse.FixedMetrics fixed = new StatsResponse.FixedMetrics(
                winLoss.overallWinRate(), List.of(), recentForm);

        StatsResponse.DeltaMetrics deltaMetrics = new StatsResponse.DeltaMetrics(
                highlight.trTrendDelta(),
                new StatsResponse.PlaystyleRelative(delta.deltaOpener(), delta.deltaPlonk(),
                        delta.deltaStride(), delta.deltaInfDs()),
                new StatsResponse.Attack(delta.deltaApp(), delta.deltaWeightedApp()),
                new StatsResponse.Defense(delta.deltaVsApm(), delta.deltaCheeseIndex()),
                highlight.strengthSplit(),
                highlight.comebackRate(),
                highlight.comebackRateAgainst(),
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
                new StatsResponse.RoundCurves(List.of(), List.of()),
                new StatsResponse.Rivals(rivalItems, 1, RIVAL_PAGE_SIZE, rivalry.rivalCount()),
                chapters);
    }

    /**
     * 랭크·TR 등은 calc의 UserSummary에서, 프로필 사진·XP·국가·가입일·플레이 시간·배지·서포터·친구 수는
     * 백엔드가 /users/{username}을 직접 불러 채운다.
     * TODO(data-eng 협의): /users/{username} 호출과 apm/pps/vs를 calc 모듈로 옮기면 백엔드 TetrioClient를 걷어낼 수 있다
     *  (지금은 레이트리미터가 calc와 따로라 두 모듈 호출이 겹치면 초당 1회를 잠깐 넘길 수 있음).
     */
    private StatsResponse.Profile profile(String normalized, UserSummary summary) {
        String sessionId = TetrioClient.newSessionId();
        TetrioClient.UserInfo user;
        try {
            user = tetrioClient.fetchUserInfo(normalized, sessionId);
        } catch (TetrioApiException e) {
            log.warn("TETR.IO 유저 정보 조회 실패 — 프로필 사진·XP·국가·가입일 생략: {}", normalized, e);
            user = TetrioClient.UserInfo.empty();
        }
        List<StatsResponse.Badge> badges = user.badges() == null ? null : user.badges().stream()
                .map(b -> new StatsResponse.Badge(b.id(), b.label(), b.desc(), b.group(), b.ts()))
                .toList();
        return new StatsResponse.Profile(summary.rank(), summary.tr(), summary.glicko(), summary.rd(),
                null, null, null,
                avatarUrl(user), user.xp(), user.country(), user.joinedAt(),
                user.gametime(), badges, user.supporter(), user.supporterTier(), user.friendCount(),
                featuredAchievements(normalized, user.featuredAchievementKeys(), sessionId));
    }

    /**
     * 대표 업적 — 걸어 둔 업적이 없으면 TETR.IO를 부르지 않고 빈 배열, 유저 정보 조회가 실패했으면 null(생략).
     * 업적 호출만 실패하면 이 필드만 생략하고 stats는 정상 응답한다.
     */
    private List<StatsResponse.FeaturedAchievement> featuredAchievements(String normalized, List<Integer> keys,
                                                                         String sessionId) {
        if (keys == null) {
            return null;
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        try {
            return tetrioClient.fetchFeaturedAchievements(normalized, keys, sessionId).stream()
                    .map(a -> new StatsResponse.FeaturedAchievement(a.k(), a.name(), a.object(), a.desc(), a.rank(),
                            a.pos(), a.total(), a.art()))
                    .toList();
        } catch (TetrioApiException e) {
            log.warn("TETR.IO 대표 업적 조회 실패 — featured_achievements 생략: {}", normalized, e);
            return null;
        }
    }

    /** TETR.IO 프로필 사진 주소 — 사진을 올린 적 없는 유저는 avatar_revision이 없어 null(응답에서 생략). */
    private static String avatarUrl(TetrioClient.UserInfo user) {
        if (user.id() == null || user.avatarRevision() == null) {
            return null;
        }
        return "https://tetr.io/user-content/avatars/" + user.id() + ".jpg?rv=" + user.avatarRevision();
    }
}
