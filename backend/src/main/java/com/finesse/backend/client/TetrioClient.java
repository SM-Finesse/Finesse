package com.finesse.backend.client;

import tools.jackson.databind.JsonNode;
import com.finesse.backend.config.TetrioProperties;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TETR.IO 공식 API 호출 — Finesse-API명세서 4.1-1절, TETR.IO API 연동확인 문서, 데이터 수집 명세 1~4장.
 * 실제 검증된 엔드포인트만 사용한다:
 *   GET /users/{username}/summaries/league
 *   GET /users/{username}/records/league/recent?limit=100&after={pri}:{sec}:{ter}
 *
 * ※ 임시 구현 — 데이터팀(위성훈, data-eng 브랜치)의 calc 모듈(CollectorProperties 등)이 완성되면
 * 그쪽으로 교체 예정 (2026-09-29 팀 확인).
 */
@Component
public class TetrioClient {

    private final WebClient webClient;
    private final TetrioProperties props;
    private final RateLimiter rateLimiter;

    public TetrioClient(WebClient tetrioWebClient, TetrioProperties props, RateLimiter rateLimiter) {
        this.webClient = tetrioWebClient;
        this.props = props;
        this.rateLimiter = rateLimiter;
    }

    public record LeagueSummary(String rank, double tr, double glicko, double rd, Double gxe, int gamesPlayed) {
    }

    public record RecordPage(List<JsonNode> entries, String nextAfterCursor) {
    }

    /**
     * 4.1절 처리 흐름 2번 — 존재하지 않는 유저는 이 호출 자체가 HTTP 404.
     */
    public LeagueSummary fetchLeagueSummary(String usernameLower, String sessionId) {
        JsonNode data;
        try {
            data = getJson("/users/" + usernameLower + "/summaries/league", sessionId);
        } catch (UserNotFoundException e) {
            throw new UserNotFoundException(usernameLower);
        }
        return new LeagueSummary(
                textOrNull(data, "rank"),
                data.path("tr").asDouble(),
                data.path("glicko").asDouble(),
                data.path("rd").asDouble(),
                data.hasNonNull("gxe") ? data.path("gxe").asDouble() : null,
                data.path("gamesplayed").asInt()
        );
    }

    /**
     * 300판/1년 창(데이터 수집 명세 3.3절)에 걸릴 때까지 after 커서로 반복 호출한다.
     * 반환값은 원시 entries 그대로 — 정규화는 RecordNormalizer가 담당한다.
     */
    public List<JsonNode> collectRecentRecords(String usernameLower, String sessionId) {
        List<JsonNode> collected = new ArrayList<>();
        Instant cutoff = Instant.now().minus(props.windowMaxDays(), ChronoUnit.DAYS);
        String after = null;

        while (collected.size() < props.windowMaxMatches()) {
            String path = "/users/" + usernameLower + "/records/league/recent?limit=" + props.pageLimit()
                    + (after != null ? "&after=" + after : "");
            JsonNode data = getJson(path, sessionId);
            JsonNode entries = data.path("entries");
            if (!entries.isArray() || entries.isEmpty()) {
                break; // 데이터 수집 명세 3.2절 — entries가 비면 종료
            }

            boolean hitPeriodLimit = false;
            for (JsonNode entry : entries) {
                Instant ts = Instant.parse(entry.path("ts").asText());
                if (ts.isBefore(cutoff)) {
                    hitPeriodLimit = true; // 3.3절 — 기간 조건에 걸린 레코드는 버리고 종료
                    break;
                }
                collected.add(entry);
                if (collected.size() >= props.windowMaxMatches()) {
                    break;
                }
            }
            if (hitPeriodLimit) {
                break;
            }

            JsonNode last = entries.get(entries.size() - 1);
            JsonNode p = last.path("p");
            if (p.isMissingNode() || p.isNull()) {
                break; // 3.2절 — 마지막 엔트리에 p가 없으면 종료
            }
            after = p.path("pri").asText() + ":" + p.path("sec").asText() + ":" + p.path("ter").asText();
        }
        return collected;
    }

    /**
     * 재시도 1회로 통일 (타임아웃 기준 문서 23번 6.1절 — 기존 파이프라인 3회/백엔드설계 2회 혼재 정리).
     * 404(유저 없음)는 재시도 대상이 아니라 즉시 던진다.
     */
    private JsonNode getJson(String path, String sessionId) {
        Exception last = null;
        for (int attempt = 0; attempt <= props.retry(); attempt++) {
            rateLimiter.await(props.minRequestIntervalMs());
            try {
                return webClient.get()
                        .uri(path)
                        .header("User-Agent", "Finesse/0.1 (team finesse-backend contact: team@finesse.example)")
                        .header("X-Session-ID", sessionId)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(root -> root.path("data"))
                        .block();
            } catch (WebClientResponseException.NotFound e) {
                throw new UserNotFoundException(path);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last instanceof WebClientResponseException wcre) {
            throw new TetrioApiException("TETR.IO API 오류 (" + wcre.getStatusCode() + "): " + path, wcre);
        }
        throw new TetrioApiException("TETR.IO API 호출 실패: " + path, last);
    }

    public static String newSessionId() {
        return "finesse-" + UUID.randomUUID();
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}
