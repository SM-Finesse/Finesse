package com.finesse.backend.client;

import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.LlmLightComment;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이트 개별·기본 코멘트 호출 (LLM 연동 명세 light-v5.2 4.3·7절) — 실제 HTTP로 확인한다.
 */
class LightCommentClientTest {

    private static final String SENTENCE = "/v1/light/sentence";
    private static final String SUMMARY = "/v1/light/summary";
    private static final Pattern STAT = Pattern.compile("\"stat\"\\s*:\\s*\"([a-z_]+)\"");

    private static final List<LlmLightComment.Item> ITEMS = List.of(
            new LlmLightComment.Item("session_vs_slope", 4.61, "높음"),
            new LlmLightComment.Item("delta_app", 0.092, "높음"),
            new LlmLightComment.Item("delta_cheese_index", -14.53, "낮음"));

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** 받은 호출 수 — sentence·summary 따로 */
    record Hits(AtomicInteger sentence, AtomicInteger summary) {
        Hits() {
            this(new AtomicInteger(), new AtomicInteger());
        }
    }

    /** 정상 LLM 서버 — 개별은 요청의 stat을 그대로 돌려준다. summaryFallback이면 기본 코멘트에 fallback=true */
    private String llm(Hits hits, boolean summaryFallback) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SENTENCE, ex -> {
            hits.sentence().incrementAndGet();
            Matcher m = STAT.matcher(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String stat = m.find() ? m.group(1) : "?";
            reply(ex, 200, "{\"stat\":\"" + stat + "\",\"sentence\":\"문장 " + stat
                    + "\",\"fallback\":false,\"version\":\"light-v5.2-guide31\"}");
        });
        server.createContext(SUMMARY, ex -> {
            hits.summary().incrementAndGet();
            ex.getRequestBody().readAllBytes();
            reply(ex, 200, "{\"light_summary\":\"요약\",\"fallback\":" + summaryFallback
                    + ",\"version\":\"light-v5.2-guide31\"}");
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 무엇을 보내도 같은 상태 코드로 답하는 서버 */
    private String status(AtomicInteger hits, int code) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            reply(ex, code, "{\"detail\":\"x\"}");
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String dead() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    private static LlmClient client(String... urls) {
        LlmProperties props = new LlmProperties(List.of(urls), 2, 10, 5, 15, 12, 3, 30,
                "/v1/comment/light", "/v1/comment/heavy-chapter");
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        WebClient.Builder builder = WebClient.builder().codecs(c -> {
            c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(mapper));
            c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
        });
        return new LlmClient(props, builder, LightCommentProperties.defaults());
    }

    private static long deadline() {
        return System.nanoTime() + 40_000_000_000L;
    }

    @Test
    void 서버_2대면_한_서버에_개별_3회_다른_서버에_기본_1회를_보내고_순서대로_조립한다() throws IOException {
        Hits h1 = new Hits();
        Hits h2 = new Hits();
        LlmClient.LightCommentOutcome out = client(llm(h1, false), llm(h2, false)).callLightComments(ITEMS, deadline());

        assertThat(out.highlights()).extracting(h -> h.stat() + ":" + h.sentence()).containsExactly(
                "session_vs_slope:문장 session_vs_slope", "delta_app:문장 delta_app", "delta_cheese_index:문장 delta_cheese_index");
        assertThat(out.lightSummary()).isEqualTo("요약");
        assertThat(out.fallbacks()).isZero();
        assertThat(out.templates()).isZero();
        assertThat(out.attemptCount()).isZero();
        assertThat(out.version()).isEqualTo("light-v5.2-guide31");
        // 한 서버는 개별 3·기본 0, 다른 서버는 개별 0·기본 1
        assertThat(List.of(h1.sentence().get() + "/" + h1.summary().get(), h2.sentence().get() + "/" + h2.summary().get()))
                .containsExactlyInAnyOrder("3/0", "0/1");
    }

    @Test
    void 서버_1대면_같은_서버에_4건을_모두_보낸다() throws IOException {
        Hits h = new Hits();
        LlmClient.LightCommentOutcome out = client(llm(h, false)).callLightComments(ITEMS, deadline());

        assertThat(h.sentence().get()).isEqualTo(3);
        assertThat(h.summary().get()).isEqualTo(1);
        assertThat(out.templates()).isZero();
    }

    @Test
    void 한_서버가_연결_실패면_다른_서버로_다시_불러_채운다() throws IOException {
        Hits h = new Hits();
        LlmClient.LightCommentOutcome out = client(dead(), llm(h, false)).callLightComments(ITEMS, deadline());

        assertThat(out.templates()).isZero();
        assertThat(out.highlights()).allMatch(x -> x.sentence().startsWith("문장 "));
        assertThat(out.lightSummary()).isEqualTo("요약");
        assertThat(h.sentence().get() + h.summary().get()).isEqualTo(4);
    }

    @Test
    void 서버_오류_5xx도_다른_서버로_다시_부른다() throws IOException {
        AtomicInteger bad = new AtomicInteger();
        Hits h = new Hits();
        LlmClient.LightCommentOutcome out = client(status(bad, 503), llm(h, false)).callLightComments(ITEMS, deadline());

        assertThat(out.templates()).isZero();
        assertThat(out.attemptCount()).isGreaterThan(0);
        assertThat(bad.get()).isGreaterThan(0);
    }

    @Test
    void 두_서버가_모두_실패하면_부록_A_문장_틀로_채우고_예외를_던지지_않는다() throws IOException {
        LlmClient.LightCommentOutcome out = client(dead(), dead()).callLightComments(ITEMS, deadline());

        assertThat(out.templates()).isEqualTo(4);
        assertThat(out.fallbacks()).isEqualTo(4);
        assertThat(out.highlights().get(0).sentence()).isEqualTo("라운드가 진행될수록 공방 능력이 뚜렷하게 오릅니다(+4.61).");
        assertThat(out.highlights().get(2).sentence()).endsWith("가비지 처리 성향이 뚜렷하게 옅습니다(-14.5).");
        // 기본 코멘트는 부록 A2 — 숫자 없이 첫머리 틀 + "~고"
        assertThat(out.lightSummary())
                .matches("(이번 분석에서 눈에 띄는 점은|대전 기록에서 두드러지는 부분은) "
                        + "라운드가 진행될수록 공방 능력이 뚜렷하게 오른다는 것입니다\\. "
                        + ".+ 블록당 공격력에서 뚜렷하게 앞서고, .+ 가비지 처리 성향이 뚜렷하게 옅습니다\\.")
                .doesNotContainPattern("\\d");
        assertThat(out.version()).isNull();
    }

    @Test
    void 형식_오류_422는_요청_문제라_다시_부르지_않고_문장_틀로_채운다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        LlmClient.LightCommentOutcome out = client(status(hits, 422)).callLightComments(ITEMS, deadline());

        assertThat(hits.get()).as("4건 모두 한 번씩만").isEqualTo(4);
        assertThat(out.templates()).isEqualTo(4);
        assertThat(out.attemptCount()).isZero();
    }

    @Test
    void LLM_서버_대체_문장도_대체_문장_수에_센다() throws IOException {
        LlmClient.LightCommentOutcome out = client(llm(new Hits(), true)).callLightComments(ITEMS, deadline());

        assertThat(out.fallbacks()).isEqualTo(1);
        assertThat(out.templates()).isZero();
        assertThat(out.lightSummary()).isEqualTo("요약");
    }
}
