package com.finesse.backend.client;

import com.finesse.backend.calc.collector.RateLimiter;
import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.config.TetrioProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드의 /users 호출이 calc 모듈과 같은 RateLimiter를 쓰는지 — 서버 전체 TETR.IO 호출이 한 줄로 서서
 * 초당 1회를 넘지 않아야 한다(법적 검토 1절, 10/7). 간격은 테스트용으로 400ms.
 */
class TetrioClientSharedLimiterTest {

    private static final long INTERVAL_MS = 400;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String tetrioStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/users/abc", ex -> {
            byte[] b = "{\"success\":true,\"data\":{\"_id\":\"id1\",\"xp\":10.5}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void calc_수집과_백엔드_users_호출이_같은_간격_줄에_선다() throws IOException {
        String base = tetrioStub();
        RateLimiter shared = new RateLimiter(new CollectorProperties(base, Duration.ofMillis(INTERVAL_MS),
                3, 100, 365, 30, 3, Duration.ofSeconds(5), Duration.ofSeconds(5)));
        TetrioClient client = new TetrioClient(WebClient.builder().baseUrl(base).build(),
                new TetrioProperties(base, 5, 0), shared);

        shared.acquire(); // calc가 방금 TETR.IO를 부름
        long started = System.nanoTime();
        TetrioClient.UserInfo info = client.fetchUserInfo("abc", "s");
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(info.xp()).isEqualTo(10.5);
        assertThat(waitedMs).as("calc 호출 직후라 간격만큼 기다린 뒤 나감").isGreaterThanOrEqualTo(INTERVAL_MS - 50);

        long next = System.nanoTime();
        shared.acquire(); // 다음 calc 호출도 백엔드 호출 뒤 간격을 지켜야 함
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - next)).isGreaterThanOrEqualTo(INTERVAL_MS - 100);
    }
}
