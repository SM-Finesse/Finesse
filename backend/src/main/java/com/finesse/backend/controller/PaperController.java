package com.finesse.backend.controller;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.config.PaperProperties;
import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.PaperInput;
import com.finesse.backend.dto.PaperInputsRequest;
import com.finesse.backend.service.PaperInputService;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 논문 실험(P0·P1 비교) 배치 추출 전용 — 서비스 흐름과 완전히 분리 (논문용 엔드포인트 추가 요청, 11/9).
 * app.paper.enabled=true일 때만 등록되고(기본 꺼짐), 모든 요청은 X-Paper-Token 헤더가 맞아야 한다.
 * 재시도·다른 서버 재요청·캐시·대체 문장·엔드포인트 마감이 모두 없다.
 */
@Hidden // 공개 Swagger 문서에 노출하지 않는다
@RestController
@RequestMapping("/api/paper")
@ConditionalOnProperty(prefix = "app.paper", name = "enabled", havingValue = "true")
public class PaperController {

    private static final Logger log = LoggerFactory.getLogger(PaperController.class);
    private static final String TOKEN_HEADER = "X-Paper-Token";

    static final Set<String> CONDITIONS = Set.of("P0", "P1");
    static final Set<Integer> SEEDS = Set.of(1, 2, 3);
    static final Set<String> SENTENCE_SLOTS = Set.of("pick1", "pick2", "pick3");
    static final Set<String> HEAVY_SLOTS = Set.of("tr_trend", "playstyle", "attack", "defense",
            "strength_split", "comeback_rate", "session_vs_slope"); // rivals 제외 7챕터

    private final PaperProperties props;
    private final PaperInputService inputService;
    private final LlmClient llmClient;
    private final JsonMapper jsonMapper;
    private final byte[] expectedToken;

    public PaperController(PaperProperties props, PaperInputService inputService, LlmClient llmClient,
                           JsonMapper jsonMapper) {
        if (props.token().isEmpty()) {
            // 외부 공개 서버 — 토큰 없이 켜지는 일이 없게 기동 자체를 막는다
            throw new IllegalStateException("app.paper.enabled=true 인데 FINESSE_PAPER_TOKEN(app.paper.token)이 비어 있습니다");
        }
        this.props = props;
        this.inputService = inputService;
        this.llmClient = llmClient;
        this.jsonMapper = jsonMapper;
        this.expectedToken = props.token().getBytes(StandardCharsets.UTF_8);
        log.warn("논문용 엔드포인트 /api/paper/** 켜짐 — 실험이 끝나면 FINESSE_PAPER_ENABLED를 끌 것");
    }

    /** 요청 1 — 입력 스냅샷. 캐시 없이 새로 계산, 실패한 유저는 error 필드로 표시하고 나머지는 정상 반환 */
    @PostMapping("/inputs")
    public ResponseEntity<?> inputs(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @RequestBody(required = false) PaperInputsRequest request) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        List<String> usernames = request == null ? null : request.usernames();
        if (usernames == null || usernames.isEmpty()) {
            return badRequest("usernames가 비어 있습니다");
        }
        if (usernames.size() > props.maxUsernamesPerRequest()) {
            return badRequest("한 번에 최대 " + props.maxUsernamesPerRequest() + "명까지 — 나눠서 요청하세요 (요청 "
                    + usernames.size() + "명)");
        }
        long startedAt = System.currentTimeMillis();
        List<PaperInput> results = inputService.snapshot(usernames);
        log.info("논문용 입력 스냅샷 {}명 완료 ({}ms, 실패 {}명)", usernames.size(),
                System.currentTimeMillis() - startedAt, results.stream().filter(r -> r.error() != null).count());
        return ResponseEntity.ok(results);
    }

    /**
     * 요청 2 — 생성 중계. 받은 본문을 그대로 llm-server /v1/paper/generate로 1회 전달하고, 응답에 llm_server를 붙인다.
     * 실패는 다시 부르지 않고 그대로 돌려준다(llm-server 오류 상태는 같은 상태 코드로, 연결 실패 502, 시간 초과 504).
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @RequestBody(required = false) JsonNode body) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        String invalid = validateGenerate(body);
        if (invalid != null) {
            return badRequest(invalid);
        }
        String server = llmClient.nextPaperServer();
        if (server == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "PAPER_LLM_UNAVAILABLE", "모든 LLM 서버가 일시 제외 상태입니다", null);
        }
        String tag = body.path("condition").asString() + "/s" + body.path("seed").asInt() + "/" + body.path("slot").asString();
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode resp = llmClient.callPaper(server, props.llmPath(), body, props.callTimeoutSeconds());
            log.info("논문용 생성 {} → {} 성공 ({}ms)", tag, server, System.currentTimeMillis() - startedAt);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(withServer(resp, server));
        } catch (WebClientResponseException e) {
            log.warn("논문용 생성 {} → {} 오류 응답 {} ({}ms)", tag, server, e.getStatusCode(), System.currentTimeMillis() - startedAt);
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(errorBodyFromLlm(e, server));
        } catch (WebClientRequestException e) {
            if (hasTimeoutCause(e)) {
                return timeout(tag, server, startedAt);
            }
            log.warn("논문용 생성 {} → {} 연결 실패: {}", tag, server, e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "PAPER_LLM_UNREACHABLE", "LLM 서버에 연결하지 못했습니다", server);
        } catch (Exception e) {
            if (hasTimeoutCause(e)) {
                return timeout(tag, server, startedAt);
            }
            log.error("논문용 생성 {} → {} 실패", tag, server, e);
            return error(HttpStatus.BAD_GATEWAY, "PAPER_LLM_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), server);
        }
    }

    /** 필드 조합 검사 — 형식만 보고 payload 안쪽은 llm-server가 검사한다 */
    static String validateGenerate(JsonNode body) {
        if (body == null || !body.isObject()) {
            return "본문이 JSON 객체가 아닙니다";
        }
        String condition = body.path("condition").isString() ? body.path("condition").asString() : null;
        if (!CONDITIONS.contains(condition)) {
            return "condition은 P0 또는 P1";
        }
        JsonNode seed = body.path("seed");
        if (!seed.isIntegralNumber() || !SEEDS.contains(seed.asInt())) {
            return "seed는 1, 2, 3 중 하나(정수)";
        }
        String callType = body.path("call_type").isString() ? body.path("call_type").asString() : null;
        String slot = body.path("slot").isString() ? body.path("slot").asString() : null;
        if (callType == null) {
            return "call_type은 sentence, summary, heavy 중 하나";
        }
        boolean slotOk = switch (callType) {
            case "sentence" -> SENTENCE_SLOTS.contains(slot);
            case "summary" -> "summary".equals(slot);
            case "heavy" -> HEAVY_SLOTS.contains(slot);
            default -> false;
        };
        if (!Set.of("sentence", "summary", "heavy").contains(callType)) {
            return "call_type은 sentence, summary, heavy 중 하나";
        }
        if (!slotOk) {
            return "call_type=" + callType + "에 맞지 않는 slot: " + slot;
        }
        if (!body.path("payload").isObject()) {
            return "payload는 JSON 객체";
        }
        return null;
    }

    private ObjectNode withServer(JsonNode resp, String server) {
        ObjectNode out;
        if (resp != null && resp.isObject()) {
            out = ((ObjectNode) resp).deepCopy();
        } else {
            out = jsonMapper.createObjectNode();
            out.set("response", resp);
        }
        out.put("llm_server", server);
        return out;
    }

    /** llm-server 오류 본문이 JSON 객체면 그대로 + llm_server, 아니면 원문을 담아서 */
    private ObjectNode errorBodyFromLlm(WebClientResponseException e, String server) {
        String raw = e.getResponseBodyAsString(StandardCharsets.UTF_8);
        try {
            JsonNode parsed = jsonMapper.readTree(raw);
            if (parsed != null && parsed.isObject()) {
                return withServer(parsed, server);
            }
        } catch (Exception ignored) {
            // JSON이 아닌 오류 본문
        }
        ObjectNode out = jsonMapper.createObjectNode();
        out.put("error_code", "PAPER_LLM_ERROR_RESPONSE");
        out.put("status", e.getStatusCode().value());
        out.put("body", raw);
        out.put("llm_server", server);
        return out;
    }

    private ResponseEntity<?> timeout(String tag, String server, long startedAt) {
        log.warn("논문용 생성 {} → {} 시간 초과 ({}ms)", tag, server, System.currentTimeMillis() - startedAt);
        return error(HttpStatus.GATEWAY_TIMEOUT, "PAPER_LLM_TIMEOUT",
                "생성이 " + props.callTimeoutSeconds() + "초 안에 끝나지 않았습니다", server);
    }

    private static boolean hasTimeoutCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "PAPER_UNAUTHORIZED", "X-Paper-Token이 없거나 맞지 않습니다", null);
    }

    private ResponseEntity<?> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, null);
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message, String server) {
        Object body = server == null
                ? new ApiErrorResponse(code, message)
                : Map.of("error_code", code, "message", message, "llm_server", server);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
