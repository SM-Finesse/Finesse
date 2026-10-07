package com.finesse.backend.client;

import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmHeavyChapterResponse;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.exception.LlmFormatException;
import com.finesse.backend.exception.LlmUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 추론 서버 호출 — 타임아웃 기준 문서(23번, v0.3) 4.2절/6.3절, Finesse-API명세서 6장/6.1절, 4.2-1절.
 *
 * 서버당 동시 처리 1건(순차)·LLM 대기열 light 우선은 9/22 확정 사항이라, 서버마다 전담 워커 스레드 1개짜리
 * 우선순위 큐를 두고 그 큐를 통해서만 실제 POST를 보낸다 — 같은 서버에 배정된 요청은 절대 동시에 나가지 않고,
 * light가 대기 중이면 이미 대기열에 있는 heavy보다 먼저 처리된다(단, 이미 서버로 나간 heavy 호출은 끊지 않음
 * — 워커가 현재 작업을 다 마친 뒤에야 다음 우선순위를 본다).
 */
@Component
public class LlmClient {

    private static final int PRIORITY_LIGHT = 0;
    private static final int PRIORITY_HEAVY = 1;

    private final LlmProperties props;
    private final Map<String, WebClient> serverClients;
    private final Map<String, ExecutorService> serverQueues;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final AtomicLong taskSeq = new AtomicLong();
    // 연결 실패한 서버를 일시 제외하기 위한 맵 (server -> 제외 해제 시각, System.nanoTime() 기준) — 5.5절
    private final Map<String, Long> excludedUntilNanos = new ConcurrentHashMap<>();

    public LlmClient(LlmProperties props, WebClient.Builder llmWebClientBuilder) {
        this.props = props;
        this.serverClients = new LinkedHashMap<>();
        this.serverQueues = new LinkedHashMap<>();
        for (String server : props.servers()) {
            this.serverClients.put(server, llmWebClientBuilder.baseUrl(server).build());
            this.serverQueues.put(server, newServerQueue());
        }
    }

    private static ExecutorService newServerQueue() {
        // corePoolSize=maxPoolSize=1 — 워커 스레드 1개가 큐를 순서대로 처리하므로 "서버당 동시 1건"이 자동 보장됨.
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(16,
                (a, b) -> Long.compare(((PrioritizedTask) a).order, ((PrioritizedTask) b).order));
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue);
    }

    private final class PrioritizedTask implements Runnable {
        final long order;
        final Runnable task;

        PrioritizedTask(int priority, Runnable task) {
            // priority를 상위 비트로 둬서 light(0)가 heavy(1)보다 항상 먼저 뽑히게 하고,
            // 같은 priority끼리는 taskSeq(도착 순서)로 안정 정렬한다.
            this.order = ((long) priority << 40) | taskSeq.getAndIncrement();
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }
    }

    private <T> T submitToServer(String server, int priority, Callable<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        serverQueues.get(server).execute(new PrioritizedTask(priority, () -> {
            try {
                future.complete(work.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }));
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * 라운드로빈으로 다음 서버를 고르되, 현재 제외 상태인 서버는 건너뛴다.
     * 전부 제외 상태면 null을 반환한다 — 호출부는 기다리지 않고 바로 끝낸다(light 503, heavy 챕터 failed). 실제 네트워크
     * 호출은 하지 않는다 (타임아웃 기준 문서 23번 5.5절 —
     * "모든 서버가 제외 상태면 즉시 503, 기다리지 않음").
     */
    private String nextAvailableServer() {
        List<String> servers = props.servers();
        int n = servers.size();
        for (int i = 0; i < n; i++) {
            String candidate = servers.get(Math.floorMod(roundRobinCounter.getAndIncrement(), n));
            if (!isExcluded(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isExcluded(String server) {
        Long until = excludedUntilNanos.get(server);
        return until != null && System.nanoTime() < until;
    }

    private void excludeServer(String server) {
        excludedUntilNanos.put(server, System.nanoTime() + props.serverExcludeSeconds() * 1_000_000_000L);
    }

    /**
     * 엔드포인트 잔여 시간이 minRemainingSeconds 미만이면 새 시도를 시작하지 않는다
     * (타임아웃 기준 문서 6.3절 마감 기준 규칙 — "재요청 최대 3회"는 상한이고 실제 횟수는 마감이 정한다).
     */
    private static boolean canStartNewAttempt(long endpointDeadlineNanos, int minRemainingSeconds) {
        long remainingMs = (endpointDeadlineNanos - System.nanoTime()) / 1_000_000;
        return remainingMs >= minRemainingSeconds * 1000L;
    }

    /**
     * scope=light — 유효 하이라이트 3개 미만·형식 오류 시 최대 3회 재요청, 최초 시도 포함 총 4회까지
     * (기능 명세서 3.3절, 타임아웃 기준 문서 23번 6.2절).
     * 유효 하이라이트 = stat이 이번 요청에 값이 있는 후보(후보 11개 평탄 키 중 생략되지 않은 것)인 것 — 밖이면 그 하이라이트만
     * 제외(v1.2 5.2절 FR-05, 값이 없는 지표는 근거가 될 수 없음). 같은 stat이 반복되면 처음 것만 센다.
     * 목표 개수는 min(3, 값이 있는 후보 수) — 후보가 3개 미만이면 몇 번을 다시 요청해도 3개가 될 수 없으므로,
     * 목표만큼 받으면(총평 포함) 재요청하지 않고 바로 끝낸다.
     * 연결 실패는 그 서버를 제외하고 다음 서버로 바로 넘어가며 시도 횟수에 넣지 않는다(23번 6.2절).
     * 재요청을 다 쓰고도(또는 마감·서버 전부 제외로 더 못 하고) 3개를 못 채우면, 받은 응답 중 총평(light_summary)이 있는
     * 것 가운데 유효 하이라이트가 가장 많은 응답을 200으로 돌려준다 — 1~2개면 그만큼, 0개면 총평만 (10/7 결정, 9/29의
     * "최종 실패 502"를 대체). 빈자리를 백엔드가 채우지 않는다(LLM이 고르지 않은 내용이 섞이므로).
     * 총평이 있는 응답을 하나도 못 받았을 때만 실패: 모든 서버가 제외 상태면 LlmUnavailableException(503, 23번 5.5절),
     * 시도가 전부 호출 1회 타임아웃이었다면 503, 응답은 받았지만 형식이 계속 틀렸다면 LlmFormatException(502).
     *
     * @param endpointDeadlineNanos light 엔드포인트(EndpointProperties.lightSeconds) 마감 시각(System.nanoTime() 기준)
     */
    public LightCommentResponse callLight(LlmLightRequest request, long endpointDeadlineNanos) {
        int callFailures = 0;
        Exception lastFailure = null;
        LightCommentResponse best = null; // 3개를 못 채웠을 때 돌려줄, 총평 있는 응답 중 유효 하이라이트가 가장 많은 것
        boolean allExcluded = false;
        Set<String> available = request.availableStats();
        int target = Math.min(3, available.size());
        int totalAttempts = props.maxRetries() + 1; // 최초 시도 1회 + 재요청 maxRetries회
        int attempt = 0;
        while (attempt < totalAttempts && canStartNewAttempt(endpointDeadlineNanos, props.lightMinRemainingSeconds())) {
            String server = nextAvailableServer();
            if (server == null) {
                allExcluded = true;
                break;
            }
            LightCommentResponse resp;
            try {
                resp = submitToServer(server, PRIORITY_LIGHT,
                        () -> postTracked(server, props.lightPath(), request, LightCommentResponse.class, props.lightCallTimeoutSeconds()));
            } catch (WebClientRequestException e) {
                lastFailure = e;
                continue; // 연결 실패 — 서버는 postTracked가 제외했고, 시도 횟수에 넣지 않고 다음 서버로
            } catch (Exception e) {
                attempt++;
                callFailures++;
                lastFailure = e;
                continue; // 호출 1회 타임아웃 등 — 재요청
            }
            attempt++;
            List<LightCommentResponse.Highlight> valid = validHighlights(resp, available);
            if (valid.size() >= 3) {
                // 3개 초과 — 응답 순서 그대로 앞 3개만 사용 (재시도 아님, LLM 전담 원칙 유지)
                return new LightCommentResponse(resp.lightSummary(), List.copyOf(valid.subList(0, 3)));
            }
            if (target < 3 && valid.size() >= target && hasSummary(resp)) {
                // 후보가 3개 미만 — 받을 수 있는 최대치를 받았으니 재요청해도 나아질 게 없다
                return new LightCommentResponse(resp.lightSummary(), valid);
            }
            if (hasSummary(resp) && (best == null || valid.size() > best.highlights().size())) {
                best = new LightCommentResponse(resp.lightSummary(), valid);
            }
            // 형식 오류·유효 하이라이트 3개 미만 — 재요청
        }
        if (best != null) {
            return best; // 하이라이트가 모자라도 총평이 정상이면 응답 전체를 실패로 돌리지 않는다
        }
        if (allExcluded) {
            throw new LlmUnavailableException("모든 LLM 서버가 일시 제외 상태 (" + attempt + "회 시도 후)", lastFailure);
        }
        if (attempt > 0 && callFailures == attempt) {
            throw new LlmUnavailableException(
                    "light 코멘트 서버가 응답하지 않음 (" + attempt + "회 시도)", lastFailure);
        }
        throw new LlmFormatException("light 코멘트 형식 오류가 " + attempt + "회 시도 후에도 지속됨 (또는 엔드포인트 잔여시간 부족)");
    }

    private static boolean hasSummary(LightCommentResponse resp) {
        return resp != null && resp.lightSummary() != null && !resp.lightSummary().isBlank();
    }

    /**
     * stat이 이번 요청에 값이 있는 후보가 아니면 FR-05 매핑 실패라 그것만 뺀다(후보 11개 키 밖이거나 생략된 지표).
     * 같은 stat이 반복되면 처음 것만 남긴다. 응답 자체가 비정상이면 빈 목록.
     */
    private static List<LightCommentResponse.Highlight> validHighlights(LightCommentResponse resp, Set<String> available) {
        if (resp == null || resp.highlights() == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        return resp.highlights().stream()
                .filter(h -> h != null && h.stat() != null && LlmLightRequest.HIGHLIGHT_STATS.contains(h.stat())
                        && available.contains(h.stat()) && seen.add(h.stat()))
                .toList();
    }

    /**
     * scope=heavy 챕터 1건 — 형식 오류 시 같은 챕터를 최대 3회까지 재시도, 최초 시도 포함 총 4회까지
     * (4.2-1절, 타임아웃 기준 문서 23번 표5). 절대 예외를 던지지 않고 status=ok/failed로 결과를 반환한다
     * (챕터 단위 부분 실패 원칙). 연결 실패는 그 서버를 제외하고 다음 서버로 바로 넘어가며 시도 횟수에 넣지 않고,
     * 모든 서버가 제외 상태면 기다리지 않고 failed로 끝낸다(23번 5.5·6.2절).
     * attempt_count는 "사용한 재요청 횟수"(0~3)를 반환한다 — 최초 시도로 바로 성공하면 0
     * (타임아웃 기준 문서 23번 7절, 현재 문서에 "1~3"으로 잘못 적혀 있던 것을 바로잡음).
     *
     * @param endpointDeadlineNanos heavy 엔드포인트(8챕터 전체) 마감 시각(System.nanoTime() 기준)
     */
    public HeavyCommentResponse.ChapterResult callHeavyChapter(String chapterId, Object data, long endpointDeadlineNanos) {
        LlmHeavyChapterRequest request = new LlmHeavyChapterRequest(chapterId, data);
        int totalAttempts = props.maxRetries() + 1; // 최초 시도 1회 + 재요청 maxRetries회
        int attempt = 0; // 시도 횟수에 넣는 호출 수 — 연결 실패는 세지 않는다(23번 6.2절)
        while (attempt < totalAttempts && canStartNewAttempt(endpointDeadlineNanos, props.heavyMinRemainingSeconds())) {
            String server = nextAvailableServer(); // 재요청도 동일한 라운드로빈 규칙, 제외 중인 서버는 건너뜀 (5.5절)
            if (server == null) {
                break; // 모든 서버 제외 상태 — 기다리지 않고 이 챕터는 failed (5.5절)
            }
            LlmHeavyChapterResponse resp;
            try {
                resp = submitToServer(server, PRIORITY_HEAVY,
                        () -> postTracked(server, props.heavyChapterPath(), request, LlmHeavyChapterResponse.class, props.heavyCallTimeoutSeconds()));
            } catch (WebClientRequestException e) {
                continue; // 연결 실패 — 서버는 postTracked가 제외, 시도 횟수에 넣지 않고 다음 서버로
            } catch (Exception e) {
                attempt++;
                continue; // 호출 1회 타임아웃 등 — 재요청. 챕터 단위는 절대 예외를 던지지 않는다
            }
            boolean valid = resp != null
                    && chapterId.equals(resp.chapterId())
                    && resp.footnote() != null
                    && !resp.footnote().isBlank();
            if (valid) {
                return new HeavyCommentResponse.ChapterResult(
                        chapterId, HeavyCommentResponse.STATUS_OK, resp.footnote(), attempt);
            }
            attempt++;
        }
        return new HeavyCommentResponse.ChapterResult(
                chapterId, HeavyCommentResponse.STATUS_FAILED, null, Math.min(attempt, props.maxRetries()));
    }

    /**
     * post()를 감싸서, 연결 자체가 실패한 경우(WebClientRequestException — connect timeout·connection
     * refused 등)만 골라 그 서버를 serverExcludeSeconds 동안 제외시킨다. 응답은 왔지만 형식이 틀렸거나
     * 응답 자체가 늦은 경우(TimeoutException)는 연결 문제가 아니므로 제외하지 않는다 (5.5절 구분 그대로).
     */
    private <T> T postTracked(String server, String path, Object body, Class<T> responseType, int timeoutSeconds) {
        try {
            return post(server, path, body, responseType, timeoutSeconds);
        } catch (WebClientRequestException e) {
            excludeServer(server);
            throw e;
        }
    }

    private <T> T post(String server, String path, Object body, Class<T> responseType, int timeoutSeconds) {
        return serverClients.get(server).post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }
}
