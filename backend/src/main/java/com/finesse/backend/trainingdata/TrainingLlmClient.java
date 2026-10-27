package com.finesse.backend.trainingdata;

import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.service.TrainingPayloadBuilder.LightItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 수집기용 LLM 코멘트 클라이언트 (10/27) — 1번 서버가 쓰는 엔드포인트를 그대로 부른다.
 * 학습 데이터용이라 시간보다 문장을 우선한다: 서버끼리 번갈아 하나씩 순서대로 보내고(1번 서비스와 GPU를 나눠 쓰므로),
 * 연결 실패·호출 시간 초과·5xx면 다른 서버로, LLM 서버가 대체 문장(fallback: true)을 주면 같은 요청을
 * fallbackRetries번 더 보낸다. 4xx는 요청 문제라 다시 보내지 않는다. 예외는 던지지 않고 결과에 사유를 남긴다.
 */
public class TrainingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(TrainingLlmClient.class);

    /**
     * 코멘트 1건 결과.
     *
     * @param text     문장 — 끝내 받지 못했으면 null
     * @param fallback LLM 서버 대체 문장이면 true (마지막으로 받은 응답 기준)
     * @param version  LLM 서버가 알려 준 문장 틀 버전
     * @param attempts 보낸 요청 수(다른 서버로 다시 보낸 것 포함)
     * @param error    받지 못한 사유(연결 실패·HTTP 422 등) — 받았으면 null
     */
    public record Reply(String text, Boolean fallback, String version, int attempts, String error) {
        public boolean ok() {
            return text != null;
        }
    }

    /** POST 한 번 — 테스트에서 바꿔 끼운다. 상태 코드와 본문, 연결 실패·시간 초과는 IOException */
    @FunctionalInterface
    public interface Transport {
        Response post(String url, byte[] body, Duration timeout) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {
    }

    private final CollectorLlmProperties props;
    private final JsonMapper mapper;
    private final Transport transport;
    private final AtomicInteger next = new AtomicInteger();

    public TrainingLlmClient(CollectorLlmProperties props, JsonMapper mapper) {
        this(props, mapper, httpTransport(props));
    }

    public TrainingLlmClient(CollectorLlmProperties props, JsonMapper mapper, Transport transport) {
        this.props = props;
        this.mapper = mapper;
        this.transport = transport;
    }

    /** 라이트 개별 코멘트 — {stat, value, zone} → sentence. 응답 stat이 보낸 것과 다르면 받지 않은 것으로 본다 */
    public Reply sentence(LightItem item) {
        return call(props.sentencePath(), item(item), "sentence", item.stat());
    }

    /** 라이트 기본 코멘트 — {items: [...]} → light_summary */
    public Reply summary(List<LightItem> items) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("items");
        items.forEach(i -> arr.add(item(i)));
        return call(props.summaryPath(), body, "light_summary", null);
    }

    /** 헤비 챕터 코멘트 — 1번 서버와 같은 {chapter_id, data} → footnote */
    public Reply heavy(LlmHeavyChapterRequest request) {
        return call(props.heavyPath(), mapper.valueToTree(request), "footnote", null);
    }

    private ObjectNode item(LightItem i) {
        ObjectNode n = mapper.createObjectNode();
        n.put("stat", i.stat());
        n.put("value", i.value());
        n.put("zone", i.zone());
        return n;
    }

    private Reply call(String path, JsonNode body, String textField, String expectStat) {
        byte[] bytes = mapper.writeValueAsBytes(body);
        Duration timeout = Duration.ofSeconds(props.callTimeoutSeconds());
        int attempts = 0;
        Reply last = null;
        for (int round = 0; round <= props.fallbackRetries(); round++) {
            String error = null;
            Reply got = null;
            // 한 번의 요청 = 서버를 돌아가며 받을 때까지(서버마다 한 번씩)
            for (int s = 0; s < props.servers().size() && got == null; s++) {
                String server = props.servers().get(Math.floorMod(next.getAndIncrement(), props.servers().size()));
                attempts++;
                try {
                    Response r = transport.post(server + path, bytes, timeout);
                    if (r.status() >= 500) {
                        error = "HTTP " + r.status();
                        continue;                       // 서버 오류 — 다른 서버로
                    }
                    if (r.status() != 200) {
                        return new Reply(null, null, null, attempts, "HTTP " + r.status()); // 요청 문제 — 다시 보내지 않음
                    }
                    JsonNode json = mapper.readTree(r.body());
                    String text = json.path(textField).asString("");
                    if (text.isBlank() || (expectStat != null && !expectStat.equals(json.path("stat").asString("")))) {
                        error = "응답 형식";
                        continue;
                    }
                    JsonNode fb = json.get("fallback");
                    got = new Reply(text, fb == null || fb.isNull() ? null : fb.asBoolean(),
                            json.path("version").asString(null), attempts, null);
                } catch (IOException e) {
                    error = e instanceof java.net.http.HttpTimeoutException ? "시간 초과" : "연결 실패";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Reply(last == null ? null : last.text(), last == null ? null : last.fallback(),
                            last == null ? null : last.version(), attempts, "중단");
                } catch (RuntimeException e) {
                    error = "응답 형식";
                }
            }
            if (got == null) {
                // 모든 서버가 실패 — 이전 회차에 대체 문장이라도 받았으면 그것을 남긴다
                return last != null ? withAttempts(last, attempts)
                        : new Reply(null, null, null, attempts, error == null ? "서버 없음" : error);
            }
            last = got;
            if (!Boolean.TRUE.equals(got.fallback()) || round == props.fallbackRetries()) {
                break;
            }
            log.debug("LLM {} 대체 문장 — 다시 요청", path);
        }
        return withAttempts(last, attempts);
    }

    private static Reply withAttempts(Reply r, int attempts) {
        return new Reply(r.text(), r.fallback(), r.version(), attempts, r.error());
    }

    private static Transport httpTransport(CollectorLlmProperties props) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
        return (url, body, timeout) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(res.statusCode(), res.body());
        };
    }
}
