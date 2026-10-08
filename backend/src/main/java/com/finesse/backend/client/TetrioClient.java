package com.finesse.backend.client;

import tools.jackson.databind.JsonNode;
import com.finesse.backend.config.TetrioProperties;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 백엔드가 TETR.IO를 직접 부르는 유일한 호출 — 프로필 패널용 유저 정보:
 *   GET /users/{username}  (프로필 사진·XP·국가·가입일·플레이 시간·친구 수)
 *
 * 리그 요약·매치 기록 수집·계산은 data-eng calc 모듈(StatCalculatorFacade)이 맡는다(라이트뷰 1차 병합, 2026-10-01).
 * TODO(data-eng 협의): 이 호출도 calc 모듈로 옮기면 레이트리미터를 하나로 합치고 이 클래스를 걷어낼 수 있다.
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

    /**
     * GET /users/{username} — 프로필 패널용 유저 정보. ts·country·avatar_revision 등은 없는 계정도 있다.
     * gametime은 유저가 숨기면 -1 — 그대로 둔다(프론트가 tr·glicko·rd처럼 음수를 숨김).
     */
    public record UserInfo(String id, Double xp, String country, Instant joinedAt, Long avatarRevision,
                           Double gametime, Integer friendCount) {

        public static UserInfo empty() {
            return new UserInfo(null, null, null, null, null, null, null);
        }
    }

    /**
     * 프로필 사진(_id + avatar_revision)·XP·국가·가입일·플레이 시간·친구 수는 이 API에서만 나온다.
     * 유저 존재 확인은 calc 모듈이 먼저 끝낸 뒤라, 여기서는 404도 일반 실패로 본다.
     */
    public UserInfo fetchUserInfo(String usernameLower, String sessionId) {
        JsonNode data;
        try {
            data = getJson("/users/" + usernameLower, sessionId);
        } catch (UserNotFoundException e) {
            throw new TetrioApiException("TETR.IO 유저 정보 없음: " + usernameLower, e);
        }
        return parseUserInfo(data);
    }

    /** 응답 해석만 따로 — 값 모양이 예상과 달라도(예: 날짜가 아닌 ts) 예외 없이 그 값만 null로 둔다. */
    static UserInfo parseUserInfo(JsonNode data) {
        Double xp = doubleOrNull(data, "xp");
        return new UserInfo(
                textOrNull(data, "_id"),
                xp != null && xp >= 0 ? xp : null, // 시스템 계정 등은 xp=-1 — 레벨 계산에 쓰면 안 되므로 생략
                textOrNull(data, "country"),
                instantOrNull(data, "ts"),
                data.path("avatar_revision").isNumber() ? data.path("avatar_revision").asLong() : null,
                doubleOrNull(data, "gametime"),
                data.path("friend_count").isNumber() ? data.path("friend_count").asInt() : null
        );
    }

    /**
     * 최초 호출 포함 retry+1회 시도 (calc 모듈의 max-retry-attempts와 같은 "총 3회"로 맞춤).
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
        return node.hasNonNull(field) ? node.path(field).asString() : null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    /** 날짜 문자열이 아니거나(false·숫자 등) 날짜로 안 읽히면 null — Instant.parse 예외로 /stats가 500이 되는 걸 막는다. */
    private static Instant instantOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isString()) {
            return null;
        }
        try {
            return Instant.parse(v.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
