package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoRateLimitedException;
import com.finesse.backend.calc.exception.TetrIoUserNotFoundException;
import com.finesse.backend.calc.metrics.AnalyticsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * TETR.IO 공식 API 호출 (설계서 3.2·3.4·3.5·3.6·3.9·3.10절).
 * 모든 요청에 X-Session-ID를 붙이고, 실제 HTTP 시도마다 RateLimiter로 호출 간격을 지킨다(재시도 포함).
 * 404는 유저 없음, 네트워크·타임아웃·5xx는 재시도 대상, 그 밖의 실패는 재시도하지 않는다.
 */
@Component
public class TetrIoApiClient implements TetrIoApi {

    static final String USER_AGENT = "Finesse-calc/0.1";
    static final String SESSION_HEADER = "X-Session-ID";
    static final String SUMMARY = "summary";
    static final String RECORDS = "records";
    /** collect 브랜치: 이보다 오래 걸린 요청은 경고 로그 */
    static final Duration SLOW_REQUEST = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TetrIoApiClient.class);

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final RawMatchParser parser;
    private final TetrIoResilience resilience;
    private final AnalyticsMetrics metrics;

    public TetrIoApiClient(CollectorProperties properties, RateLimiter rateLimiter, RawMatchParser parser,
                           TetrIoResilience resilience, AnalyticsMetrics metrics) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
        this.rateLimiter = rateLimiter;
        this.parser = parser;
        this.resilience = resilience;
        this.metrics = metrics;
    }

    @Override
    public UserSummary fetchLeagueSummary(String username, String sessionId) {
        JsonNode data = get(SUMMARY, username, sessionId,
                b -> b.path("/users/{username}/summaries/league").build(username));
        return parseSummary(username, data);
    }

    /** summaries/league 응답의 data 노드 → UserSummary (02 3.2절·부록 C.3). gameswon이 없으면 0 (v4.1). */
    static UserSummary parseSummary(String username, JsonNode data) {
        return new UserSummary(
                username,
                data.hasNonNull("rank") ? data.path("rank").asString() : null,
                data.path("tr").asDouble(),
                data.path("glicko").asDouble(),
                data.path("rd").asDouble(),
                data.hasNonNull("gxe") ? data.path("gxe").asDouble() : null,
                data.path("gamesplayed").asInt(),
                data.hasNonNull("gameswon") ? data.path("gameswon").asInt() : 0,
                data.hasNonNull("apm") ? data.path("apm").asDouble() : null,
                data.hasNonNull("pps") ? data.path("pps").asDouble() : null,
                data.hasNonNull("vs") ? data.path("vs").asDouble() : null
        );
    }

    @Override
    public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
        JsonNode data = get(RECORDS, username, sessionId, b -> b
                .path("/users/{username}/records/league/recent")
                .queryParam("limit", limit)
                .queryParamIfPresent("after", Optional.ofNullable(afterCursor))
                .build(username));
        return parser.parsePage(data);
    }

    private JsonNode get(String endpoint, String username, String sessionId, Function<UriBuilder, URI> uri) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("X-Session-ID가 없습니다 (설계서 3.5절)");
        }
        return resilience.call(() -> fetchOnceMeasured(endpoint, username, sessionId, uri));
    }

    /** HTTP 1회 시도의 결과와 지연시간을 기록한다 (설계서 27.3절). RateLimiter 대기 시간은 제외한다. */
    private JsonNode fetchOnceMeasured(String endpoint, String username, String sessionId,
                                       Function<UriBuilder, URI> uri) {
        rateLimiter.acquire();
        long start = System.nanoTime();
        String outcome = "failure";
        try {
            JsonNode data = fetchOnce(username, sessionId, uri);
            outcome = "success";
            return data;
        } catch (TetrIoUserNotFoundException e) {
            outcome = "not_found";
            throw e;
        } catch (TetrIoApiException e) {
            outcome = e.retryable() ? "retryable_failure" : "failure";
            // collect 브랜치: 실패한 요청을 남긴다 — 예외 메시지에 요청 URL(닉네임)이 들어갈 수 있어 원인은 클래스 이름만
            log.warn("TETR.IO {} 요청 실패 — {}{} ({}초)", endpoint, e.getMessage(), rootCauseName(e),
                    seconds(System.nanoTime() - start));
            throw e;
        } finally {
            long took = System.nanoTime() - start;
            metrics.recordApiCall(endpoint, outcome, took);
            if ("success".equals(outcome) && took >= SLOW_REQUEST.toNanos()) {
                // collect 브랜치: 실패 없이 느리기만 한 요청 — 응답이 조금씩 오거나 DNS 조회가 늦으면 타임아웃(5초)에 안 걸린다
                log.warn("TETR.IO {} 요청이 {}초 걸렸습니다(기준 {}초)", endpoint, seconds(took), SLOW_REQUEST.toSeconds());
            }
        }
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? "" : " [" + root.getClass().getSimpleName() + "]";
    }

    private static double seconds(long nanos) {
        return Math.round(nanos / 1_000_000.0) / 1000.0;
    }

    /** HTTP 1회 시도 — 재시도마다 다시 불린다. */
    private JsonNode fetchOnce(String username, String sessionId, Function<UriBuilder, URI> uri) {
        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uri)
                    .header(SESSION_HEADER, sessionId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new TetrIoUserNotFoundException(username);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new TetrIoRateLimitedException(e);   // collect 브랜치: 429를 따로 알린다(재시도 안 함은 그대로)
        } catch (HttpServerErrorException e) {
            throw new TetrIoApiException("TETR.IO 서버 오류 " + e.getStatusCode(), e, true);
        } catch (ResourceAccessException e) {
            throw new TetrIoApiException("TETR.IO 연결 실패 또는 타임아웃", e, true);
        } catch (RestClientException e) {
            throw new TetrIoApiException("TETR.IO API 호출 실패", e, false);   // 4xx(429 포함)·응답 변환 실패
        }
        if (root == null || (root.has("success") && !root.path("success").asBoolean())) {
            throw new TetrIoApiException("TETR.IO API 응답 success=false");
        }
        return root.path("data");
    }
}