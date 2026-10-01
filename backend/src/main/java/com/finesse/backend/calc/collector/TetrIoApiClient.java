package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoUserNotFoundException;
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

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final RawMatchParser parser;
    private final TetrIoResilience resilience;

    public TetrIoApiClient(CollectorProperties properties, RateLimiter rateLimiter, RawMatchParser parser,
                           TetrIoResilience resilience) {
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
    }

    @Override
    public UserSummary fetchLeagueSummary(String username, String sessionId) {
        JsonNode data = get(username, sessionId,
                b -> b.path("/users/{username}/summaries/league").build(username));
        return new UserSummary(
                username,
                data.hasNonNull("rank") ? data.path("rank").asString() : null,
                data.path("tr").asDouble(),
                data.path("glicko").asDouble(),
                data.path("rd").asDouble(),
                data.hasNonNull("gxe") ? data.path("gxe").asDouble() : null,
                data.path("gamesplayed").asInt()
        );
    }

    @Override
    public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
        JsonNode data = get(username, sessionId, b -> b
                .path("/users/{username}/records/league/recent")
                .queryParam("limit", limit)
                .queryParamIfPresent("after", Optional.ofNullable(afterCursor))
                .build(username));
        return parser.parsePage(data);
    }

    private JsonNode get(String username, String sessionId, Function<UriBuilder, URI> uri) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("X-Session-ID가 없습니다 (설계서 3.5절)");
        }
        return resilience.call(() -> fetchOnce(username, sessionId, uri));
    }

    /** HTTP 1회 시도 — 재시도마다 다시 불린다. */
    private JsonNode fetchOnce(String username, String sessionId, Function<UriBuilder, URI> uri) {
        rateLimiter.acquire();
        JsonNode root;
        try {
            root = restClient.get()
                    .uri(uri)
                    .header(SESSION_HEADER, sessionId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new TetrIoUserNotFoundException(username);
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