package com.finesse.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 인메모리 캐시(Caffeine) 설정 — Finesse-API명세서 5장.
 * 캐시 키는 username 단위로 stats / comment-light / comment-heavy 3종을 분리해서 쓴다.
 * TTL은 최대 ttlMinutes(기본 10분) 유지 후 자동 소멸 — 만료되면 다음 요청에서 자동 재조회된다.
 * comment(light/heavy)는 stats보다 나중에 채워지지만 별도의 TTL 타이머를 갖지 않는다 —
 * StatsService.getStats()가 stats를 새로 계산할 때마다 comment 캐시도 함께 폐기해서
 * 캐시 만료/갱신 기준을 stats 하나로 통일한다 (5절, "stats는 만료됐는데 comment는
 * 살아있는" 불일치 방지).
 */
@Configuration
public class CacheConfig {

    public static final String STATS_CACHE = "stats";
    public static final String COMMENT_LIGHT_CACHE = "comment-light";
    public static final String COMMENT_HEAVY_CACHE = "comment-heavy";
    /**
     * 대기열(app.queue.enabled)로 미리 만든 코멘트 중 comment-light·comment-heavy에 넣지 않는 것(대체 문장·실패 챕터가 섞임) —
     * 대기표 done 뒤 다시 부를 때 LLM을 또 부르지 않도록 끝난 대기표를 지우는 시간(2분) 동안만 쓴다(「전적 검색 대기열 문서」 4.2·4.3절).
     * stats가 새로 계산되면 comment 캐시와 함께 비운다.
     */
    public static final String COMMENT_LIGHT_PREPARED_CACHE = "comment-light-prepared";
    public static final String COMMENT_HEAVY_PREPARED_CACHE = "comment-heavy-prepared";

    @Bean
    public CacheManager cacheManager(AppCacheProperties appCacheProperties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(STATS_CACHE, COMMENT_LIGHT_CACHE, COMMENT_HEAVY_CACHE,
                COMMENT_LIGHT_PREPARED_CACHE, COMMENT_HEAVY_PREPARED_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(appCacheProperties.ttlMinutes(), TimeUnit.MINUTES));
        return manager;
    }
}
