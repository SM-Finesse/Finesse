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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 전부 제외 상태면 null을 반환한다 — 호출부는 이걸 "이번 시도 실패"로 처리해서, 실제 네트워크
     * 호출을 시도하지 않고 즉시 다음(또는 종료) 단계로 넘어간다 (타임아웃 기준 문서 23번 5.5절 —
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
     * scope=light — 형식 오류(하이라이트 3개 미만/초과) 시 최대 3회 재요청, 최초 시도 포함 총 4회까지
     * (기능 명세서 3.3절, 타임아웃 기준 문서 23번 표5 — "3회 재요청(최초 포함 4회)").
     * 재시도가 전부 네트워크/타임아웃 실패였다면 LlmUnavailableException(503),
     * 서버 응답은 받았지만 형식이 계속 틀렸다면 LlmFormatException(502)로 구분해서 던진다.
     *
     * @param endpointDeadlineNanos light 엔드포인트(EndpointProperties.lightSeconds) 마감 시각(System.nanoTime() 기준)
     */
    public LightCommentResponse callLight(LlmLightRequest request, long endpointDeadlineNanos) {
        int networkFailures = 0;
        Exception lastNetworkFailure = null;
        int totalAttempts = props.maxRetries() + 1; // 최초 시도 1회 + 재요청 maxRetries회
        int attempt = 0;
        for (; attempt < totalAttempts && canStartNewAttempt(endpointDeadlineNanos, props.lightMinRemainingSeconds()); attempt++) {
            String server = nextAvailableServer();
            if (server == null) {
                // 모든 서버가 제외 상태 — 실제 호출 없이 즉시 이번 시도 실패 처리 (5.5절, 기다리지 않음)
                networkFailures++;
                lastNetworkFailure = new LlmUnavailableException("모든 LLM 서버가 일시 제외 상태", null);
                continue;
            }
            LightCommentResponse resp;
            try {
                resp = submitToServer(server, PRIORITY_LIGHT,
                        () -> postTracked(server, props.lightPath(), request, LightCommentResponse.class, props.lightCallTimeoutSeconds()));
            } catch (Exception e) {
                networkFailures++;
                lastNetworkFailure = e;
                continue; // 연결 실패·타임아웃 — 재시도
            }
            if (resp == null || resp.highlights() == null) {
                continue; // 형식 오류 — 재시도
            }
            if (resp.highlights().size() < 3) {
                continue; // 3개 미만(형식오류) — 재요청
            }
            if (resp.highlights().size() > 3) {
                // 3개 초과 — 응답 순서 그대로 앞 3개만 사용 (재시도 아님, LLM 전담 원칙 유지)
                return new LightCommentResponse(resp.lightSummary(), resp.highlights().subList(0, 3));
            }
            return resp; // 정확히 3개
        }
        if (attempt > 0 && networkFailures == attempt) {
            throw new LlmUnavailableException(
                    "light 코멘트 서버에 연결할 수 없음 (" + attempt + "회 시도)", lastNetworkFailure);
        }
        throw new LlmFormatException("light 코멘트 형식 오류가 " + attempt + "회 재시도 후에도 지속됨 (또는 엔드포인트 잔여시간 부족)");
    }

    /**
     * scope=heavy 챕터 1건 — 형식 오류 시 같은 챕터를 최대 3회까지 재시도, 최초 시도 포함 총 4회까지
     * (4.2-1절, 타임아웃 기준 문서 23번 표5). 절대 예외를 던지지 않고 status=ok/failed로 결과를 반환한다
     * (챕터 단위 부분 실패 원칙).
     * attempt_count는 "사용한 재요청 횟수"(0~3)를 반환한다 — 최초 시도로 바로 성공하면 0
     * (타임아웃 기준 문서 23번 7절, 현재 문서에 "1~3"으로 잘못 적혀 있던 것을 바로잡음).
     *
     * @param endpointDeadlineNanos heavy 엔드포인트(8챕터 전체) 마감 시각(System.nanoTime() 기준)
     */
    public HeavyCommentResponse.ChapterResult callHeavyChapter(String chapterId, Object data, long endpointDeadlineNanos) {
        LlmHeavyChapterRequest request = new LlmHeavyChapterRequest(chapterId, data);
        int totalAttempts = props.maxRetries() + 1; // 최초 시도 1회 + 재요청 maxRetries회
        int attempt = 0;
        for (; attempt < totalAttempts && canStartNewAttempt(endpointDeadlineNanos, props.heavyMinRemainingSeconds()); attempt++) {
            String server = nextAvailableServer(); // 재요청도 동일한 라운드로빈 규칙, 제외 중인 서버는 건너뜀 (5.5절)
            if (server == null) {
                continue; // 모든 서버 제외 상태 — 실제 호출 없이 즉시 이번 시도 실패 처리
            }
            LlmHeavyChapterResponse resp;
            try {
                resp = submitToServer(server, PRIORITY_HEAVY,
                        () -> postTracked(server, props.heavyChapterPath(), request, LlmHeavyChapterResponse.class, props.heavyCallTimeoutSeconds()));
            } catch (Exception e) {
                continue; // 연결 실패·타임아웃·형식 오류 모두 "이번 시도 실패" — 챕터 단위는 절대 예외를 던지지 않는다
            }
            boolean valid = resp != null
                    && chapterId.equals(resp.chapterId())
                    && resp.footnote() != null
                    && !resp.footnote().isBlank();
            if (valid) {
                return new HeavyCommentResponse.ChapterResult(
                        chapterId, HeavyCommentResponse.STATUS_OK, resp.footnote(), attempt);
            }
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
