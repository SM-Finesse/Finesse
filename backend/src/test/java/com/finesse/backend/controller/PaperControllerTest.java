package com.finesse.backend.controller;

import com.finesse.backend.client.LlmClient;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.PaperProperties;
import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.PaperInput;
import com.finesse.backend.dto.PaperInputsRequest;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.UserNotFoundException;
import com.finesse.backend.service.PaperInputService;
import com.finesse.backend.service.StatsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 논문용 /api/paper/** — 토큰 검사, 요청 형식 검사, 그대로 중계(재시도·다른 서버 재요청 없음), llm_server 표시.
 * 실제 HTTP로 확인한다(LlmClientTest와 같은 방식).
 */
class PaperControllerTest {

    private static final String PAPER_PATH = "/v1/paper/generate";
    private static final String TOKEN = "secret-token";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private String llmServer(AtomicInteger hits, AtomicReference<String> lastBody, int status, String body, long delayMs)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PAPER_PATH, ex -> {
            hits.incrementAndGet();
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String deadServer() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    private static LlmClient llmClient(String... urls) {
        LlmProperties props = new LlmProperties(List.of(urls), 2, 10, 5, 15, 12, 3, 30,
                "/v1/comment/light", "/v1/comment/heavy-chapter");
        WebClient.Builder builder = WebClient.builder().codecs(c -> {
            c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(MAPPER));
            c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(MAPPER));
        });
        return new LlmClient(props, builder);
    }

    private static PaperProperties props(int timeoutSeconds) {
        return new PaperProperties(true, TOKEN, PAPER_PATH, timeoutSeconds, 5, "abc123");
    }

    private static PaperController controller(LlmClient llm, int timeoutSeconds) {
        return new PaperController(props(timeoutSeconds), null, llm, MAPPER);
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    private static final String SENTENCE_REQ = """
            {"condition":"P0","seed":1,"call_type":"sentence","slot":"pick1",
             "payload":{"stat":"delta_inf_ds","value":0.31,"zone":"높음"}}""";

    @Test
    void 토큰이_없으면_기동하지_않는다() {
        PaperProperties noToken = new PaperProperties(true, "  ", null, null, null, null);
        assertThatThrownBy(() -> new PaperController(noToken, null, llmClient("http://127.0.0.1:1"), MAPPER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 토큰이_틀리면_401이고_LLM을_부르지_않는다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String a = llmServer(hits, new AtomicReference<>(), 200, "{}", 0);
        PaperController c = controller(llmClient(a), 5);

        assertThat(c.generate(null, json(SENTENCE_REQ)).getStatusCode().value()).isEqualTo(401);
        assertThat(c.generate("wrong", json(SENTENCE_REQ)).getStatusCode().value()).isEqualTo(401);
        assertThat(c.inputs("wrong", new PaperInputsRequest(List.of("a"))).getStatusCode().value()).isEqualTo(401);
        assertThat(hits.get()).isZero();
    }

    @Test
    void 요청_형식이_틀리면_400() {
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ))).isNull();
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ.replace("\"P0\"", "\"P2\"")))).isNotNull();
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ.replace("\"seed\":1", "\"seed\":4")))).isNotNull();
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ.replace("\"seed\":1", "\"seed\":\"1\"")))).isNotNull();
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ.replace("pick1", "summary")))).isNotNull();
        assertThat(PaperController.validateGenerate(json(
                "{\"condition\":\"P1\",\"seed\":2,\"call_type\":\"summary\",\"slot\":\"summary\",\"payload\":{\"items\":[]}}")))
                .isNull();
        assertThat(PaperController.validateGenerate(json(
                "{\"condition\":\"P1\",\"seed\":3,\"call_type\":\"heavy\",\"slot\":\"tr_trend\",\"payload\":{}}")))
                .isNull();
        // rivals는 실험에서 빠진 챕터
        assertThat(PaperController.validateGenerate(json(
                "{\"condition\":\"P1\",\"seed\":3,\"call_type\":\"heavy\",\"slot\":\"rivals\",\"payload\":{}}")))
                .isNotNull();
        assertThat(PaperController.validateGenerate(json(SENTENCE_REQ.replace("\"payload\":", "\"x\":")))).isNotNull();
    }

    @Test
    void 받은_본문을_그대로_보내고_응답에_llm_server를_붙인다() throws IOException {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        AtomicReference<String> bodyA = new AtomicReference<>();
        String a = llmServer(hitsA, bodyA, 200, "{\"raw_output\":\"문장\",\"finish_reason\":\"stop\"}", 0);
        String b = llmServer(hitsB, new AtomicReference<>(), 200, "{\"raw_output\":\"B\"}", 0);
        PaperController c = controller(llmClient(a, b), 5);

        ResponseEntity<?> r1 = c.generate(TOKEN, json(SENTENCE_REQ));
        ResponseEntity<?> r2 = c.generate(TOKEN, json(SENTENCE_REQ));

        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        JsonNode body1 = (JsonNode) r1.getBody();
        assertThat(body1.path("raw_output").asString()).isEqualTo("문장");
        assertThat(body1.path("finish_reason").asString()).isEqualTo("stop");
        assertThat(body1.path("llm_server").asString()).isEqualTo(a);
        assertThat(((JsonNode) r2.getBody()).path("llm_server").asString()).isEqualTo(b); // 라운드로빈
        assertThat(json(bodyA.get())).isEqualTo(json(SENTENCE_REQ));
        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).isEqualTo(1);
    }

    @Test
    void LLM_서버_오류는_다시_부르지_않고_같은_상태로_돌려준다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String a = llmServer(hits, new AtomicReference<>(), 502, "{\"error_code\":\"LLM_FORMAT_ERROR\",\"raw_output\":\"x\"}", 0);
        PaperController c = controller(llmClient(a), 5);

        ResponseEntity<?> r = c.generate(TOKEN, json(SENTENCE_REQ));

        assertThat(r.getStatusCode().value()).isEqualTo(502);
        JsonNode body = (JsonNode) r.getBody();
        assertThat(body.path("error_code").asString()).isEqualTo("LLM_FORMAT_ERROR");
        assertThat(body.path("raw_output").asString()).isEqualTo("x");
        assertThat(body.path("llm_server").asString()).isEqualTo(a);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 연결_실패해도_다른_서버로_넘기지_않는다() throws IOException {
        String dead = deadServer();
        AtomicInteger hitsB = new AtomicInteger();
        String b = llmServer(hitsB, new AtomicReference<>(), 200, "{}", 0);
        PaperController c = controller(llmClient(dead, b), 5);

        ResponseEntity<?> r = c.generate(TOKEN, json(SENTENCE_REQ));

        assertThat(r.getStatusCode().value()).isEqualTo(502);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body).containsEntry("error_code", "PAPER_LLM_UNREACHABLE").containsEntry("llm_server", dead);
        assertThat(hitsB.get()).isZero();

        // 서비스용 서버 제외 표시도 하지 않는다 — 다음 라운드로빈은 그대로 dead 다음 순서(b), 그 다음 다시 dead
        assertThat(((JsonNode) c.generate(TOKEN, json(SENTENCE_REQ)).getBody()).path("llm_server").asString()).isEqualTo(b);
        @SuppressWarnings("unchecked")
        Map<String, Object> third = (Map<String, Object>) c.generate(TOKEN, json(SENTENCE_REQ)).getBody();
        assertThat(third).containsEntry("llm_server", dead);
    }

    @Test
    void 호출_제한을_넘기면_504() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String a = llmServer(hits, new AtomicReference<>(), 200, "{}", 2500);
        PaperController c = controller(llmClient(a), 1);

        ResponseEntity<?> r = c.generate(TOKEN, json(SENTENCE_REQ));

        assertThat(r.getStatusCode().value()).isEqualTo(504);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body).containsEntry("error_code", "PAPER_LLM_TIMEOUT");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 입력_스냅샷은_유저별로_실패를_표시하고_나머지는_돌려준다() {
        StatsService stats = mock(StatsService.class);
        TetrioClient tetrio = mock(TetrioClient.class);
        when(stats.computeUncached("ghost")).thenThrow(new UserNotFoundException("ghost"));
        when(stats.computeUncached("newbie")).thenReturn(new StatsResponse("newbie", true, 4, Instant.now(),
                null, null, null, null, null, null));
        when(tetrio.fetchUserInfo(anyString(), anyString())).thenReturn(TetrioClient.UserInfo.empty());
        PaperInputService service = new PaperInputService(stats, tetrio, props(5));
        PaperController c = new PaperController(props(5), service, llmClient("http://127.0.0.1:1"), MAPPER);

        ResponseEntity<?> r = c.inputs(TOKEN, new PaperInputsRequest(List.of("ghost", "newbie", "bad name!")));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<PaperInput> results = (List<PaperInput>) r.getBody();
        assertThat(results).extracting(p -> p.error().errorCode())
                .containsExactly("USER_NOT_FOUND", "INSUFFICIENT_MATCHES", "INVALID_USERNAME");
    }

    @Test
    void 입력_스냅샷은_한_번에_받는_인원을_제한한다() {
        PaperController c = controller(llmClient("http://127.0.0.1:1"), 5);
        ResponseEntity<?> r = c.inputs(TOKEN, new PaperInputsRequest(List.of("a", "b", "c", "d", "e", "f")));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse) r.getBody()).errorCode()).isEqualTo("BAD_REQUEST");
    }
}
