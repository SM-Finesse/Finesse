import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실제 LLM 추론 서버가 아직 없어서, 지연시간 허용조건(타임아웃) 재설계를 위해
 * "학교 PC에서 로컬로 도는 추론 서버 1대는 한 번에 요청 하나씩만 순차 처리한다"는 가정으로
 * 인위적 지연을 흉내낸다. (com.sun.net.httpserver.HttpServer는 executor를 null로 두면
 * 포트별로 요청을 순차 처리하므로 "서버 1대=단일 처리"를 그대로 재현한다.)
 *
 * 지연 분포는 타임아웃 기준 문서(23번, v0.3) 2절의 Vulkan 실측(GPU 실측 보고서·출력 토큰
 * 예산 보고서)을 따른다 — light 최악 9.2~10.2초, heavy 챕터 최악 2.7~2.8초. 90%는 그 정상
 * 범위, 10%는 콜드스타트/부하 상황을 가정해 호출 1회 타임아웃(light 15초/heavy 10초)을 넘나드는
 * 값을 섞어서 재시도·마감 기준 규칙(6.3절)이 실제로 동작하는지 확인할 수 있게 한다.
 * 파인튜닝 모델로 재측정되면 이 범위도 같이 갱신해야 한다(10.2절).
 *
 * 평소 개발 중엔 매번 몇 초씩 기다리면 불편하므로 기본은 즉시 응답(지연 없음)이고,
 * 타임아웃 설정을 다시 검증하고 싶을 때만 환경변수로 켠다:
 *   set SIMULATE_LLM_DELAY=true && java tools\MockLlmServer.java
 */
public class MockLlmServer {

    private static final boolean SIMULATE_DELAY = "true".equalsIgnoreCase(System.getenv("SIMULATE_LLM_DELAY"));

    private static void simulateInferenceDelay(String port, String path) {
        if (!SIMULATE_DELAY) {
            return;
        }
        boolean heavy = path.contains("heavy-chapter");
        boolean slow = ThreadLocalRandom.current().nextInt(100) < 10;
        long delayMs = heavy
                ? (slow ? ThreadLocalRandom.current().nextLong(8000, 14000) : ThreadLocalRandom.current().nextLong(1000, 3000))
                : (slow ? ThreadLocalRandom.current().nextLong(12000, 20000) : ThreadLocalRandom.current().nextLong(3000, 10000));
        System.out.println("[" + port + "]" + path + " delay=" + delayMs + "ms" + (slow ? " (slow)" : ""));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int[] ports = {9101, 9102};
        for (int port : ports) {
            String portLabel = String.valueOf(port);
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/v1/comment/light", ex -> {
                simulateInferenceDelay(portLabel, "/light");
                String body = "{\"light_summary\":\"mock summary\",\"highlights\":["
                        + "{\"stat\":\"delta_plonk\",\"sentence\":\"s1\"},"
                        + "{\"stat\":\"comeback_rate\",\"sentence\":\"s2\"},"
                        + "{\"stat\":\"tr_trend_delta\",\"sentence\":\"s3\"}]}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.createContext("/v1/comment/heavy-chapter", ex -> {
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"chapter_id\"\\s*:\\s*\"([^\"]+)\"").matcher(reqBody);
                String chapterId = m.find() ? m.group(1) : "unknown";
                simulateInferenceDelay(portLabel, "/heavy-chapter[" + chapterId + "]");
                String body = "{\"chapter_id\":\"" + chapterId + "\",\"footnote\":\"mock footnote for " + chapterId + "\"}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.setExecutor(null);
            server.start();
            System.out.println("Mock LLM server on port " + port);
        }
        Thread.sleep(Long.MAX_VALUE);
    }
}
