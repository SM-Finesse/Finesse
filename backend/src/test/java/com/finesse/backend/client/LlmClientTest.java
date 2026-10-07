package com.finesse.backend.client;

import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.exception.LlmUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmClient 재요청 규칙 — 타임아웃 기준 문서(23번) 5.5·6.2절, LLM/AI 파트 설계 v1.2 5.2절.
 * 실제 HTTP로 확인한다(연결 실패는 아무도 듣지 않는 포트로 만든다).
 */
class LlmClientTest {

    private static final String LIGHT_PATH = "/v1/comment/light";
    private static final String HEAVY_PATH = "/v1/comment/heavy-chapter";

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    /** 요청마다 bodies에서 하나씩 꺼내 응답한다(마지막 것은 계속 재사용). 받은 요청 수를 센다. */
    private String llmServer(AtomicInteger hits, String path, String... bodies) throws IOException {
        Deque<String> queue = new ArrayDeque<>(List.of(bodies));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, ex -> {
            hits.incrementAndGet();
            String body = queue.size() > 1 ? queue.poll() : queue.peek();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
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
            return "http://127.0.0.1:" + s.getLocalPort(); // 닫은 포트 — 연결 거부
        }
    }

    private static LlmClient client(int maxRetries, String... urls) {
        LlmProperties props = new LlmProperties(List.of(urls), 2, 10, 5, 15, 12, maxRetries, 30, LIGHT_PATH, HEAVY_PATH);
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        WebClient.Builder builder = WebClient.builder().codecs(c -> {
            c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(mapper));
            c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
        });
        return new LlmClient(props, builder);
    }

    private static long deadline() {
        return System.nanoTime() + 40_000_000_000L;
    }

    private static String light(String... stats) {
        StringBuilder sb = new StringBuilder("{\"light_summary\":\"요약\",\"highlights\":[");
        for (int i = 0; i < stats.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"stat\":\"").append(stats[i]).append("\",\"sentence\":\"s").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static final LlmLightRequest REQUEST = new LlmLightRequest(
            new LlmLightRequest.FixedMetrics(0.5, List.of()),
            new LlmLightRequest.DeltaMetrics(null, null, null, null, null, 0.0));

    @Test
    void 후보_11개_밖_stat은_빼고_남은_것에서_3개를_쓴다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String url = llmServer(hits, LIGHT_PATH, light("tr_trend_delta", "delta_plonk", "delta_comeback", "session_vs_slope"));

        LightCommentResponse r = client(3, url).callLight(REQUEST, deadline());

        assertThat(r.highlights()).extracting(LightCommentResponse.Highlight::stat)
                .containsExactly("delta_plonk", "delta_comeback", "session_vs_slope");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 유효_하이라이트가_3개_미만이면_재요청() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String url = llmServer(hits, LIGHT_PATH,
                light("comeback_rate", "delta_plonk", "delta_app"),
                light("delta_vs_apm", "delta_plonk", "delta_app"));

        LightCommentResponse r = client(3, url).callLight(REQUEST, deadline());

        assertThat(r.highlights()).extracting(LightCommentResponse.Highlight::stat)
                .containsExactly("delta_vs_apm", "delta_plonk", "delta_app");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void 재요청을_다_써도_3개가_안_되면_총평과_가장_많이_받은_하이라이트로_200() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String url = llmServer(hits, LIGHT_PATH,
                light("delta_plonk", "comeback_rate"),
                light("delta_plonk", "delta_app"),
                light("tr_trend_delta"),
                light("delta_vs_apm"));

        LightCommentResponse r = client(3, url).callLight(REQUEST, deadline());

        assertThat(r.lightSummary()).isEqualTo("요약");
        assertThat(r.highlights()).extracting(LightCommentResponse.Highlight::stat)
                .containsExactly("delta_plonk", "delta_app"); // 빈자리를 채우지 않는다
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test
    void 유효_하이라이트가_하나도_없어도_총평이_있으면_총평만_200() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String url = llmServer(hits, LIGHT_PATH, light("comeback_rate", "tr_trend_delta"));

        LightCommentResponse r = client(1, url).callLight(REQUEST, deadline());

        assertThat(r.lightSummary()).isEqualTo("요약");
        assertThat(r.highlights()).isEmpty();
    }

    @Test
    void 총평이_있는_응답을_하나도_못_받으면_502() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String url = llmServer(hits, LIGHT_PATH, "{\"highlights\":[]}");

        assertThatThrownBy(() -> client(1, url).callLight(REQUEST, deadline()))
                .isInstanceOf(com.finesse.backend.exception.LlmFormatException.class);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void 연결_실패는_시도_횟수에_넣지_않고_다음_서버로() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String live = llmServer(hits, LIGHT_PATH, light("delta_opener", "delta_plonk", "delta_app"));

        // 재요청 0회(시도 1회)여도 첫 서버 연결 실패는 세지 않으므로 두 번째 서버에서 성공해야 한다
        LightCommentResponse r = client(0, deadServer(), live).callLight(REQUEST, deadline());

        assertThat(r.highlights()).hasSize(3);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 모든_서버가_연결_실패로_제외되면_바로_503() throws IOException {
        LlmClient client = client(3, deadServer(), deadServer());

        assertThatThrownBy(() -> client.callLight(REQUEST, deadline())).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void heavy도_연결_실패는_재요청_횟수에_넣지_않는다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        String live = llmServer(hits, HEAVY_PATH, "{\"chapter_id\":\"attack\",\"footnote\":\"각주\"}");

        HeavyCommentResponse.ChapterResult r = client(0, deadServer(), live).callHeavyChapter("attack", null, deadline());

        assertThat(r.status()).isEqualTo(HeavyCommentResponse.STATUS_OK);
        assertThat(r.attemptCount()).isEqualTo(0);
    }

    @Test
    void heavy는_모든_서버가_제외되면_기다리지_않고_failed() throws IOException {
        HeavyCommentResponse.ChapterResult r = client(3, deadServer()).callHeavyChapter("attack", null, deadline());

        assertThat(r.status()).isEqualTo(HeavyCommentResponse.STATUS_FAILED);
        assertThat(r.attemptCount()).isEqualTo(0);
    }
}
