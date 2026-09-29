package com.finesse.backend.service;

import com.finesse.backend.client.RecordNormalizer;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.CacheConfig;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.model.NormalizedMatch;
import com.finesse.backend.service.calc.RivalAggregator;
import com.finesse.backend.service.calc.StatsCalculator;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET /api/v1/stats/{username} 오케스트레이션 — Finesse-API명세서 4.1절 처리 흐름 그대로.
 */
@Service
public class StatsService {

    private final TetrioClient tetrioClient;
    private final RecordNormalizer normalizer;
    private final StatsCalculator calculator;
    private final RivalAggregator rivalAggregator;
    private final CacheManager cacheManager;
    private final EndpointProperties endpointProperties;
    private final ExecutorService statsExecutor;

    public StatsService(TetrioClient tetrioClient, RecordNormalizer normalizer, StatsCalculator calculator,
                         RivalAggregator rivalAggregator, CacheManager cacheManager,
                         EndpointProperties endpointProperties, @Qualifier("statsExecutor") ExecutorService statsExecutor) {
        this.tetrioClient = tetrioClient;
        this.normalizer = normalizer;
        this.calculator = calculator;
        this.rivalAggregator = rivalAggregator;
        this.cacheManager = cacheManager;
        this.endpointProperties = endpointProperties;
        this.statsExecutor = statsExecutor;
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
    private StatsResponse compute(String normalized) {
        Future<StatsResponse> future = statsExecutor.submit(() -> computeInternal(normalized));
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

    private StatsResponse computeInternal(String normalized) {
        String sessionId = TetrioClient.newSessionId();
        TetrioClient.LeagueSummary summary = tetrioClient.fetchLeagueSummary(normalized, sessionId);

        StatsResponse.Profile profile = new StatsResponse.Profile(
                summary.rank(), summary.tr(), summary.glicko(), summary.rd());

        if (calculator.isColdStart(summary.gamesPlayed())) {
            // FR-03 콜드스타트 처리 — Δ 계산 자체를 생략하지만, win_rate/recent_form은 표본 하한이 없는
            // fixed 지표라 있는 만큼(10판 미만)은 그대로 계산해서 채운다 (기능 명세서 3절, 라이트뷰 승패 카드 반영).
            List<JsonNode> coldRaw = tetrioClient.collectRecentRecords(normalized, sessionId);
            List<NormalizedMatch> coldMatches = new ArrayList<>();
            normalizer.normalize(coldRaw, normalized, coldMatches);
            StatsResponse.FixedMetrics coldFixedMetrics = new StatsResponse.FixedMetrics(
                    calculator.winRate(coldMatches), List.of(), calculator.recentForm(coldMatches));
            return new StatsResponse(normalized, true, summary.gamesPlayed(), profile,
                    coldFixedMetrics,
                    null, new StatsResponse.RoundCurves(List.of(), List.of()),
                    new StatsResponse.Rivals(List.of(), 1, 20, 0),
                    Map.of("note", "콜드스타트 — 챕터 데이터 없음"));
        }

        List<JsonNode> raw = tetrioClient.collectRecentRecords(normalized, sessionId);
        List<NormalizedMatch> matches = new ArrayList<>();
        var meta = normalizer.normalize(raw, normalized, matches);

        StatsResponse.FixedMetrics fixedMetrics = new StatsResponse.FixedMetrics(
                calculator.winRate(matches), calculator.trTrendSeries(matches), calculator.recentForm(matches));

        StatsResponse.RoundCurves roundCurves = calculator.roundCurves(matches);

        StatsResponse.DeltaMetrics deltaMetrics = new StatsResponse.DeltaMetrics(
                calculator.trTrendDelta(matches),
                new StatsResponse.PlaystyleRelative(null, null, null, null), // TODO: statrank 공식 미확정
                calculator.attackDelta(matches),
                calculator.defenseDelta(matches),
                calculator.strengthSplit(matches),
                calculator.comebackRate(matches),
                calculator.sessionVsSlope(roundCurves)
        );

        StatsResponse.Rivals rivals = rivalAggregator.aggregate(matches);

        Map<String, Object> chapters = Map.of(
                "dropped_records", meta.droppedRecords(),
                "missing_tr_matches", meta.missingTrMatches(),
                "note", "8챕터 차트 데이터 세부 스키마는 [협의 필요] — 우선 계산 원자료(round_curves, rivals, delta_metrics)로 구성 가능"
        );

        return new StatsResponse(normalized, false, matches.size(), profile, fixedMetrics,
                deltaMetrics, roundCurves, rivals, chapters);
    }
}
