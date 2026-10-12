package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoRateLimitedException;
import com.finesse.backend.calc.metrics.AnalyticsMetrics;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.finesse.backend.calc.fixture.RawMatchFixtures.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * collect 브랜치 — 429를 RATE_LIMITED로 구분해 위로 알리는지. 재시도하지 않는 것·PARTIAL이면 그대로 분석하는 것은 기존과 같다.
 */
class RateLimitedCollectionTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    /** 페이지 3개 × 2판 = 최대 6판, 부분 수집 최소 3판 */
    private static final CollectorProperties PROPS = new CollectorProperties(
            "http://unused", Duration.ZERO, 3, 2, 365, 3, 3, Duration.ofSeconds(3), Duration.ofSeconds(5));

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 모든 요청에 status로 답하는 TETR.IO 대역, 받은 요청 수를 센다 */
    private TetrIoApiClient clientAnswering(int status, int[] hits) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits[0]++;
            byte[] body = "{\"success\":false}".getBytes();
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        CollectorProperties props = new CollectorProperties(base, Duration.ZERO, 3, 100, 365, 30, 3,
                Duration.ofSeconds(3), Duration.ofSeconds(5));
        return new TetrIoApiClient(props, new RateLimiter(props), new RawMatchParser(),
                new TetrIoResilience(3, Duration.ofMillis(1), TetrIoResilience.defaultCircuitBreakerConfig()),
                AnalyticsMetrics.noop());
    }

    @Test
    void 응답이_429면_요청_제한_예외이고_재시도하지_않는다() throws IOException {
        int[] hits = {0};
        TetrIoApiClient client = clientAnswering(429, hits);

        assertThatThrownBy(() -> client.fetchLeagueSummary("abc", "s")).isInstanceOf(TetrIoRateLimitedException.class);
        assertThat(hits[0]).isEqualTo(1);
    }

    @Test
    void 응답이_500이면_요청_제한이_아니고_기존처럼_재시도한다() throws IOException {
        int[] hits = {0};
        TetrIoApiClient client = clientAnswering(500, hits);

        assertThatThrownBy(() -> client.fetchLeagueSummary("abc", "s"))
                .isInstanceOf(TetrIoApiException.class).isNotInstanceOf(TetrIoRateLimitedException.class);
        assertThat(hits[0]).isEqualTo(3);
    }

    /** total판, failAtCall번째 기록 호출에서 error를 던지는 가짜 API */
    private static TetrIoApi fakeApi(int total, int failAtCall, RuntimeException error) {
        return new TetrIoApi() {
            int calls = 0;

            @Override
            public UserSummary fetchLeagueSummary(String username, String sessionId) {
                return null;
            }

            @Override
            public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
                calls++;
                if (calls == failAtCall) {
                    throw error;
                }
                int start = afterCursor == null ? 0 : Integer.parseInt(afterCursor);
                List<RawMatch> page = new ArrayList<>();
                for (int i = start; i < Math.min(total, start + limit); i++) {
                    RawMatch m = valid("m" + i, 0, "uid-" + (i % 3), "n");
                    page.add(new RawMatch(m.matchId(), NOW.minus(Duration.ofHours(i + 1)),
                            m.result(), m.me(), m.opponent(), m.rounds()));
                }
                return new RecordPage(page, 0, start + limit < total ? String.valueOf(start + limit) : null);
            }
        };
    }

    private static CollectionStatus statusOf(int total, int failAtCall, RuntimeException error) {
        return new TetrIoCollector(fakeApi(total, failAtCall, error), PROPS).collectMatches("u", "s", NOW).status();
    }

    @Test
    void 기록_수집이_429로_실패해_분석할_수_없으면_RATE_LIMITED() {
        TetrIoRateLimitedException limited = new TetrIoRateLimitedException(null);

        assertThat(statusOf(20, 1, limited)).isEqualTo(CollectionStatus.RATE_LIMITED);   // 첫 페이지부터 실패 (FAILED 자리)
        assertThat(statusOf(20, 2, limited)).isEqualTo(CollectionStatus.RATE_LIMITED);   // 2판만 확보 (INSUFFICIENT 자리)
        assertThat(statusOf(20, 3, limited)).isEqualTo(CollectionStatus.PARTIAL);        // 4판 확보 — 기존처럼 분석
    }

    @Test
    void 요청_제한이_아닌_실패는_기존_판정_그대로() {
        TetrIoApiException other = new TetrIoApiException("5xx");

        assertThat(statusOf(20, 1, other)).isEqualTo(CollectionStatus.FAILED);
        assertThat(statusOf(20, 2, other)).isEqualTo(CollectionStatus.INSUFFICIENT);
        assertThat(statusOf(20, 3, other)).isEqualTo(CollectionStatus.PARTIAL);
    }
}
