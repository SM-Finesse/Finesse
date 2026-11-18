package com.finesse.backend.client;

import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.LlmLightComment;
import com.finesse.backend.queue.CommentQueue;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버별 LLM 대기열 순서 — 「전적 검색 대기열 문서」 4.3절.
 * Q14: 대기열 켬이면 라이트·헤비를 들어온 순서대로(라이트가 먼저 온 헤비를 앞지르지 않음).
 * Q19: 꺼짐이면 지금처럼 라이트 먼저(타임아웃 기준 정리 문서 QA 8-1).
 * 실제 HTTP 서버 1대 — 첫 호출을 붙잡아 워커를 바쁘게 만든 사이에 헤비 → 라이트 순으로 넣고, 서버가 받은 순서를 본다.
 */
class LlmQueueOrderTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private String llmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(path.endsWith("heavy-chapter") ? "heavy:" + (body.contains("\"tr_trend\"") ? "first" : "next")
                    : path.endsWith("summary") ? "light:summary" : "light:sentence");
            if (received.size() == 1) {
                try {
                    release.await(5, TimeUnit.SECONDS); // 첫 호출을 붙잡아 서버 워커를 바쁘게
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String resp = path.endsWith("heavy-chapter") ? "{\"chapter_id\":\"x\",\"footnote\":\"f\"}"
                    : path.endsWith("summary") ? "{\"light_summary\":\"s\"}" : "{\"stat\":\"delta_app\",\"sentence\":\"t\"}";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static LlmClient client(String url, QueueProperties queue) {
        LlmProperties props = new LlmProperties(List.of(url), 2, 10, 5, 15, 12, 0, 30,
                "/v1/comment/light", "/v1/comment/heavy-chapter");
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        WebClient.Builder builder = WebClient.builder().codecs(c -> {
            c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(mapper));
            c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
        });
        return new LlmClient(props, builder, LightCommentProperties.defaults(), queue);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("시간 초과");
            }
            Thread.sleep(10);
        }
    }

    private List<String> order(QueueProperties queue) throws Exception {
        LlmClient llm = client(llmServer(), queue);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> llm.callHeavyChapter("tr_trend", java.util.Map.of(), deadline));
        await(() -> received.size() == 1);
        CompletableFuture<?> heavy = CompletableFuture.runAsync(() -> llm.callHeavyChapter("attack", java.util.Map.of(), deadline));
        await(() -> llm.pendingWorkSeconds() > LlmClient.INITIAL_HEAVY_CALL_SECONDS); // 헤비가 대기열에 들어감
        CompletableFuture<?> light = CompletableFuture.runAsync(() -> llm.callLightComments(
                List.of(new LlmLightComment.Item("delta_app", 0.05, "높음")), deadline));
        await(() -> llm.pendingWorkSeconds() > 3 * LlmClient.INITIAL_LIGHT_CALL_SECONDS);
        release.countDown();
        CompletableFuture.allOf(first, heavy, light).get(10, TimeUnit.SECONDS);
        return received.subList(1, received.size());
    }

    @Test
    void Q14_대기열_켬이면_먼저_온_헤비가_라이트보다_먼저_나간다() throws Exception {
        assertThat(order(QueueProperties.enabledDefaults()))
                .containsExactly("heavy:next", "light:summary", "light:sentence");
    }

    @Test
    void Q19_대기열_꺼짐이면_지금처럼_라이트가_먼저() throws Exception {
        assertThat(order(QueueProperties.disabled()))
                .containsExactly("light:summary", "light:sentence", "heavy:next");
    }

    @Test
    void 대기열_켬이면_기다리는_사이_마감이_지난_호출은_LLM에_보내지_않는다() throws Exception {
        LlmClient llm = client(llmServer(), QueueProperties.enabledDefaults());
        long far = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> llm.callHeavyChapter("tr_trend", java.util.Map.of(), far));
        await(() -> received.size() == 1); // 서버 워커를 붙잡음
        long soon = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        CompletableFuture<com.finesse.backend.dto.HeavyCommentResponse.ChapterResult> late = CompletableFuture.supplyAsync(
                () -> llm.callHeavyChapter("attack", java.util.Map.of(), soon));
        Thread.sleep(2000); // 대기열에서 기다리는 사이 마감이 지남
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertThat(late.get(10, TimeUnit.SECONDS).status()).isEqualTo("failed");
        assertThat(received).as("마감이 지난 호출은 보내지 않음").containsExactly("heavy:first");
    }

    @Test
    void 대기열에_든_호출로_LLM_부하와_배정할_수_있는_서버_수를_알려_준다() throws Exception {
        LlmClient llm = client(llmServer(), QueueProperties.enabledDefaults());
        assertThat(llm.assignableServers()).isEqualTo(1);
        assertThat(llm.pendingWorkSeconds()).isZero();
        assertThat(llm.jobWorkSeconds(CommentQueue.Kind.HEAVY)).isEqualTo(8 * LlmClient.INITIAL_HEAVY_CALL_SECONDS);
        assertThat(llm.jobDurationSeconds(CommentQueue.Kind.LIGHT)).isEqualTo(4 * LlmClient.INITIAL_LIGHT_CALL_SECONDS);
    }
}
