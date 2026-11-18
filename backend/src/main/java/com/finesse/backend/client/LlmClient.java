package com.finesse.backend.client;

import com.finesse.backend.config.LightCommentProperties;
import com.finesse.backend.config.LlmProperties;
import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmHeavyChapterResponse;
import com.finesse.backend.dto.LlmLightComment;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.exception.LlmFormatException;
import com.finesse.backend.exception.LlmTimeoutException;
import com.finesse.backend.exception.LlmUnavailableException;
import com.finesse.backend.queue.CommentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 추론 서버 호출 — 타임아웃 기준 문서(23번) 4.2절/6.3절, Finesse-API명세서 6장/6.1절, 4.2-1절.
 *
 * 서버당 동시 처리 1건(순차)·LLM 대기열 light 우선은 9/22 확정 사항이라, 서버마다 전담 워커 스레드 1개짜리
 * 우선순위 큐를 두고 그 큐를 통해서만 실제 POST를 보낸다 — 같은 서버에 배정된 요청은 절대 동시에 나가지 않고,
 * light가 대기 중이면 이미 대기열에 있는 heavy보다 먼저 처리된다(단, 이미 서버로 나간 heavy 호출은 끊지 않음
 * — 워커가 현재 작업을 다 마친 뒤에야 다음 우선순위를 본다).
 * <p>
 * 대기열(app.queue.enabled=true)을 켜면 라이트 우선을 없애고 라이트·헤비를 들어온 순서대로 보낸다(「전적 검색 대기열 문서」 4.3절,
 * PM 결정 10/29). 꺼져 있으면 지금처럼 라이트 먼저(타임아웃 기준 정리 문서 QA 8-1).
 * 대기열 입장 판정용으로 서버별 대기열의 부하(종류별 호출 1회 처리 시간의 이동 평균 × 대기 중인 호출 수)를 알려 준다.
 */
@Component
public class LlmClient implements CommentQueue.LlmLoad {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final int PRIORITY_LIGHT = 0;
    private static final int PRIORITY_HEAVY = 1;

    /** 서버 대기열에 넣는 호출의 종류 — 우선순위와 부하 계산에 쓴다 */
    enum CallKind { LIGHT, HEAVY }

    // 호출 1회 처리 시간 이동 평균(초)의 처음값 기본 — app.queue.initial-*-call-seconds와 같은 값.
    // 11/18 실제 LLM 서버 실측: light 약 1.5초, heavy 챕터 약 3.4초(문서 1.1 표의 약 2초보다 김)
    static final double INITIAL_LIGHT_CALL_SECONDS = 1.5;
    static final double INITIAL_HEAVY_CALL_SECONDS = 3.5;
    // 이동 평균 α — 문서 4.3절은 작업(light 4회·heavy 8회) 단위 α 0.3. 호출마다 갱신하므로 작업 1건을 다 넣었을 때
    // 0.3만큼 움직이도록 호출 α = 1 − 0.7^(1/호출 수). 호출마다 0.3이면 대체 문장처럼 빨리 끝난 호출 몇 개로 예상이 크게 흔들린다(11/18 실측)
    static final double LIGHT_CALL_ALPHA = 1 - Math.pow(0.7, 1.0 / 4);
    static final double HEAVY_CALL_ALPHA = 1 - Math.pow(0.7, 1.0 / 8);

    private final LlmProperties props;
    private final LightCommentProperties lightComment;
    private final boolean fifo; // 대기열 켬 — 라이트 우선 없앰
    // 라이트 개별·기본 코멘트 4건을 동시에 기다리는 용도 — 실제 POST는 여전히 서버별 대기열(서버당 1건)로만 나간다
    private final ExecutorService lightFanout = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, WebClient> serverClients;
    private final Map<String, ThreadPoolExecutor> serverQueues;
    // 서버별 지금 나가 있는 호출 — 부하 계산용 (서버 워커가 넣고 뺀다)
    private final Map<String, RunningCall> runningCalls = new ConcurrentHashMap<>();
    private volatile double lightCallSeconds;
    private volatile double heavyCallSeconds;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final AtomicLong taskSeq = new AtomicLong();
    // 연결 실패한 서버를 일시 제외하기 위한 맵 (server -> 제외 해제 시각, System.nanoTime() 기준) — 5.5절
    private final Map<String, Long> excludedUntilNanos = new ConcurrentHashMap<>();

    public LlmClient(LlmProperties props, WebClient.Builder llmWebClientBuilder) {
        this(props, llmWebClientBuilder, LightCommentProperties.defaults());
    }

    public LlmClient(LlmProperties props, WebClient.Builder llmWebClientBuilder, LightCommentProperties lightComment) {
        this(props, llmWebClientBuilder, lightComment, QueueProperties.disabled());
    }

    @Autowired
    public LlmClient(LlmProperties props, WebClient.Builder llmWebClientBuilder, LightCommentProperties lightComment,
                     QueueProperties queue) {
        this.props = props;
        this.lightComment = lightComment;
        this.fifo = queue.enabled();
        this.lightCallSeconds = queue.initialLightCallSeconds();
        this.heavyCallSeconds = queue.initialHeavyCallSeconds();
        this.serverClients = new LinkedHashMap<>();
        this.serverQueues = new LinkedHashMap<>();
        for (String server : props.servers()) {
            this.serverClients.put(server, llmWebClientBuilder.baseUrl(server).build());
            this.serverQueues.put(server, newServerQueue());
        }
    }

    private static ThreadPoolExecutor newServerQueue() {
        // corePoolSize=maxPoolSize=1 — 워커 스레드 1개가 큐를 순서대로 처리하므로 "서버당 동시 1건"이 자동 보장됨.
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(16,
                (a, b) -> Long.compare(((PrioritizedTask) a).order, ((PrioritizedTask) b).order));
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue);
    }

    private final class PrioritizedTask implements Runnable {
        final long order;
        final CallKind kind;
        final Runnable task;

        PrioritizedTask(int priority, CallKind kind, Runnable task) {
            this.kind = kind;
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

    private <T> T submitToServer(String server, CallKind kind, Callable<T> work) throws Exception {
        return await(enqueue(server, kind, work, 0L));
    }

    private <T> T submitToServer(String server, CallKind kind, Callable<T> work, long endpointDeadlineNanos) throws Exception {
        return await(enqueue(server, kind, work, endpointDeadlineNanos));
    }

    /** 대기열 켬: 라이트·헤비 같은 우선순위(들어온 순서대로). 꺼짐: 라이트 먼저 */
    int priorityOf(CallKind kind) {
        return switch (kind) {
            case LIGHT -> PRIORITY_LIGHT;
            case HEAVY -> fifo ? PRIORITY_LIGHT : PRIORITY_HEAVY;
        };
    }

    /**
     * 서버 대기열에 넣기만 하고 바로 돌아온다 — 넣은 순서가 곧 보내는 순서(같은 우선순위끼리).
     * @param endpointDeadlineNanos 이 호출이 속한 엔드포인트 마감(0이면 없음). 대기열 켬일 때 차례가 왔는데 마감이 이미 지났으면
     *                              보내지 않는다 — 마감 뒤 결과는 어차피 버리므로(heavy는 timeout으로 채움, light는 문장 틀) GPU만 쓴다.
     *                              대기열 꺼짐이면 지금처럼 보낸다
     */
    private <T> CompletableFuture<T> enqueue(String server, CallKind kind, Callable<T> work, long endpointDeadlineNanos) {
        CompletableFuture<T> future = new CompletableFuture<>();
        serverQueues.get(server).execute(new PrioritizedTask(priorityOf(kind), kind, () -> {
            if (fifo && endpointDeadlineNanos != 0L && System.nanoTime() >= endpointDeadlineNanos) {
                future.completeExceptionally(new TimeoutException("엔드포인트 마감이 지나 보내지 않음"));
                return;
            }
            long startedAt = System.nanoTime();
            runningCalls.put(server, new RunningCall(kind, startedAt));
            boolean record = true;
            try {
                future.complete(work.call());
            } catch (WebClientRequestException e) {
                record = false; // 연결 실패는 바로 끝나 처리 시간이 아니다 — 평균에 넣지 않는다(그 서버는 제외됨)
                future.completeExceptionally(e);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                runningCalls.remove(server);
                if (record) {
                    recordCall(kind, (System.nanoTime() - startedAt) / 1e9);
                }
            }
        }));
        return future;
    }

    private record RunningCall(CallKind kind, long startedNanos) {
    }

    private void recordCall(CallKind kind, double seconds) {
        switch (kind) {
            case LIGHT -> lightCallSeconds = LIGHT_CALL_ALPHA * seconds + (1 - LIGHT_CALL_ALPHA) * lightCallSeconds;
            case HEAVY -> heavyCallSeconds = HEAVY_CALL_ALPHA * seconds + (1 - HEAVY_CALL_ALPHA) * heavyCallSeconds;
        }
    }

    private double callSeconds(CallKind kind) {
        return kind == CallKind.HEAVY ? heavyCallSeconds : lightCallSeconds;
    }

    // ---- 대기열 입장 판정용 부하 (CommentQueue.LlmLoad, 「전적 검색 대기열 문서」 4.3절) ----

    /** 연결 실패로 30초 제외된 서버를 뺀 서버 수 (Q18) */
    @Override
    public int assignableServers() {
        int n = 0;
        for (String server : props.servers()) {
            if (!isExcluded(server)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 서버별 대기열에 들어간 서비스 호출(진행 중 포함)의 예상 처리 시간 합.
     * 연결 실패로 제외된 서버의 대기열은 넣지 않는다 — 그 호출들은 연결 실패로 바로 끝나 다른 서버로 넘어가므로,
     * 넣으면 서버 수(분모)는 줄였는데 분자는 그대로라 예상 대기가 실제보다 길게 나온다.
     */
    @Override
    public double pendingWorkSeconds() {
        double sum = 0;
        long now = System.nanoTime();
        for (Map.Entry<String, ThreadPoolExecutor> e : serverQueues.entrySet()) {
            if (isExcluded(e.getKey())) {
                continue;
            }
            for (Runnable r : e.getValue().getQueue().toArray(new Runnable[0])) {
                if (r instanceof PrioritizedTask p) {
                    sum += callSeconds(p.kind);
                }
            }
            RunningCall rc = runningCalls.get(e.getKey());
            if (rc != null) {
                double expected = callSeconds(rc.kind());
                double elapsed = (now - rc.startedNanos()) / 1e9;
                sum += Math.max(expected * 0.25, expected - elapsed);
            }
        }
        return sum;
    }

    @Override
    public double jobWorkSeconds(CommentQueue.Kind kind) {
        return kind == CommentQueue.Kind.LIGHT ? 4 * lightCallSeconds : 8 * heavyCallSeconds;
    }

    @Override
    public double jobDurationSeconds(CommentQueue.Kind kind) {
        int servers = Math.max(1, assignableServers());
        // light: 서버 2대면 A가 개별 3회를 차례로(B는 기본 1회를 동시에), 1대면 4회. heavy: 8챕터를 서버 수만큼 나눠 차례로
        return kind == CommentQueue.Kind.LIGHT
                ? (servers >= 2 ? 3 : 4) * lightCallSeconds
                : Math.ceil(8.0 / servers) * heavyCallSeconds;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
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
     * 시도가 전부 호출 1회 타임아웃(응답 자체가 없음)이었다면 503, 응답은 받았지만 형식이 계속 틀렸다면(추론 서버의
     * 502 등 오류 상태 응답 포함) LlmFormatException(502).
     *
     * @param endpointDeadlineNanos light 엔드포인트(EndpointProperties.lightSeconds) 마감 시각(System.nanoTime() 기준)
     */
    public LightCommentResponse callLight(LlmLightRequest request, long endpointDeadlineNanos) {
        int callFailures = 0;
        Exception lastFailure = null;
        LightCommentResponse best = null; // 3개를 못 채웠을 때 돌려줄, 총평 있는 응답 중 유효 하이라이트가 가장 많은 것
        boolean allExcluded = false;
        boolean inputTooLarge = false;
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
                resp = submitToServer(server, CallKind.LIGHT,
                        () -> postTracked(server, props.lightPath(), request, LightCommentResponse.class, props.lightCallTimeoutSeconds()));
            } catch (WebClientRequestException e) {
                lastFailure = e;
                continue; // 연결 실패 — 서버는 postTracked가 제외했고, 시도 횟수에 넣지 않고 다음 서버로
            } catch (WebClientResponseException e) {
                attempt++;
                lastFailure = e;
                if (isInputTooLarge(e)) {
                    inputTooLarge = true;
                    break; // 413 입력 길이 초과 — 같은 요청은 다시 보내도 결과가 같으므로 재요청하지 않는다
                }
                continue; // 서버가 오류 상태로 응답(추론 서버는 형식 오류를 502로 줌) — 응답은 받았으니 형식 오류로 세고 재요청
            } catch (Exception e) {
                attempt++;
                callFailures++;
                lastFailure = e;
                continue; // 호출 1회 타임아웃 등 응답 자체가 없음 — 재요청
            }
            attempt++;
            int retries = attempt - 1; // attempt_count = 사용한 재요청 횟수(heavy와 같은 기준, QA R-01)
            List<LightCommentResponse.Highlight> valid = validHighlights(resp, available);
            if (valid.size() >= 3) {
                // 3개 초과 — 응답 순서 그대로 앞 3개만 사용 (재시도 아님, LLM 전담 원칙 유지)
                return new LightCommentResponse(resp.lightSummary(), List.copyOf(valid.subList(0, 3)), retries);
            }
            if (target < 3 && valid.size() >= target && hasSummary(resp)) {
                // 후보가 3개 미만 — 받을 수 있는 최대치를 받았으니 재요청해도 나아질 게 없다
                return new LightCommentResponse(resp.lightSummary(), valid, retries);
            }
            if (hasSummary(resp) && (best == null || valid.size() > best.highlights().size())) {
                best = new LightCommentResponse(resp.lightSummary(), valid);
            }
            // 형식 오류·유효 하이라이트 3개 미만 — 재요청
        }
        if (best != null) {
            // 하이라이트가 모자라도 총평이 정상이면 응답 전체를 실패로 돌리지 않는다 — 재요청은 쓴 만큼 전부
            return new LightCommentResponse(best.lightSummary(), best.highlights(), Math.max(0, attempt - 1));
        }
        if (allExcluded) {
            throw new LlmUnavailableException("모든 LLM 서버가 일시 제외 상태 (" + attempt + "회 시도 후)", lastFailure);
        }
        if (attempt > 0 && callFailures == attempt) {
            throw new LlmUnavailableException(
                    "light 코멘트 서버가 응답하지 않음 (" + attempt + "회 시도)", lastFailure);
        }
        // 재요청을 다 쓰기 전에 엔드포인트 마감이 가까워 멈췄으면 504 — 형식 오류(502)와 원인을 구분한다(QA 2차 제안, 10/12)
        if (!inputTooLarge && attempt < totalAttempts
                && !canStartNewAttempt(endpointDeadlineNanos, props.lightMinRemainingSeconds())) {
            throw new LlmTimeoutException("light 코멘트 엔드포인트 마감 — " + attempt + "회 시도 후 남은 시간 부족");
        }
        throw new LlmFormatException("light 코멘트 형식 오류가 " + attempt + "회 시도 후에도 지속됨");
    }

    /**
     * 라이트 개별·기본 코멘트 결과.
     *
     * @param highlights   입력 순서(pick1 → pick3) 그대로 — 지표마다 문장이 하나씩 반드시 있다
     * @param attemptCount 다른 서버로 다시 부른 횟수(0~3, heavy·예전 light와 같은 범위로 자름)
     * @param fallbacks    대체 문장 수(LLM 서버 대체 문장 + 백엔드 문장 틀), 0~4
     * @param templates    그중 백엔드 문장 틀(부록 A)로 채운 수 — 두 서버 모두 실패했거나 시간이 모자란 호출
     * @param version      LLM 서버가 알려 준 문장 틀 버전(예: light-v5.6d-p1rules-20261027), 받은 응답이 없으면 null
     */
    public record LightCommentOutcome(String lightSummary, List<LightCommentResponse.Highlight> highlights,
                                      int attemptCount, int fallbacks, int templates, String version) {
    }

    /**
     * scope=light (light-v5.2~v5.6d) — calc가 고른 지표(1~3개)마다 개별 코멘트 1회 + 묶음 기본 코멘트 1회를 동시에 부른다
     * (LLM 연동 명세 light-v5.2 4.3·7절, 10/21).
     * 서버 2대면 A = 개별 3회, B = 기본 1회. 서버 1대면 같은 서버에 기본을 먼저, 이어서 개별 3회를 넣는다.
     * 실제 POST는 서버별 대기열(서버당 1건, 대기열 꺼짐이면 light 우선)로만 나가고, 호출 1회 제한은 10초(LLM 서버가 9초 안에 응답).
     * LLM 서버는 내용 문제를 스스로 처리해 항상 200(검사 실패면 fallback=true 대체 문장)이므로, 백엔드는
     * 연결 실패·호출 시간 초과·5xx만 다른 서버로 1회 다시 부른다(엔드포인트에 minRemaining 이상 남았을 때). 422 등 4xx는
     * 요청 버그라 다시 부르지 않는다. 끝내 못 받은 호출과 마감까지 안 끝난 호출은 부록 A1·A2 문장 틀로 채운다 — 절대 예외를 던지지 않는다.
     */
    public LightCommentOutcome callLightComments(List<LlmLightComment.Item> items, long endpointDeadlineNanos) {
        AtomicInteger retries = new AtomicInteger();
        String a = nextAvailableServer();
        String b = a == null ? null : nextAvailableServer();
        CompletableFuture<LlmLightComment.SummaryResponse> summary = CompletableFuture.completedFuture(null);
        List<CompletableFuture<LlmLightComment.SentenceResponse>> sentences = new ArrayList<>();
        if (a != null) {
            String summaryServer = b == null ? a : b; // 2대면 B, 1대면 같은 서버에 기본 먼저(넣은 순서대로 보낸다)
            summary = dispatchLightComment(summaryServer, lightComment.summaryPath(),
                    new LlmLightComment.SummaryRequest(items), LlmLightComment.SummaryResponse.class,
                    endpointDeadlineNanos, retries);
            for (LlmLightComment.Item item : items) {
                sentences.add(dispatchLightComment(a, lightComment.sentencePath(), item,
                        LlmLightComment.SentenceResponse.class, endpointDeadlineNanos, retries));
            }
        }
        List<CompletableFuture<?>> all = new ArrayList<>(sentences);
        all.add(summary);
        try {
            long waitMs = Math.max(0, (endpointDeadlineNanos - System.nanoTime()) / 1_000_000);
            CompletableFuture.allOf(all.toArray(CompletableFuture<?>[]::new)).get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("light 코멘트 엔드포인트 마감 — 끝나지 않은 호출은 문장 틀로 채움");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // dispatchLightComment는 예외 대신 null을 돌려주므로 오지 않는다
        }

        Random rng = ThreadLocalRandom.current();
        int fallbacks = 0;
        int templates = 0;
        String version = null;
        List<LightCommentResponse.Highlight> highlights = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            LlmLightComment.Item item = items.get(i);
            LlmLightComment.SentenceResponse r = sentences.isEmpty() ? null : sentences.get(i).getNow(null);
            String sentence;
            if (r != null && item.stat().equals(r.stat()) && r.sentence() != null && !r.sentence().isBlank()) {
                sentence = r.sentence();
                fallbacks += Boolean.TRUE.equals(r.fallback()) ? 1 : 0;
                version = version != null ? version : r.version();
            } else {
                sentence = LightCommentTemplates.sentence(item.stat(), item.value(), item.zone(), rng);
                fallbacks++;
                templates++;
            }
            highlights.add(new LightCommentResponse.Highlight(item.stat(), sentence));
        }
        LlmLightComment.SummaryResponse s = summary.getNow(null);
        String lightSummary;
        if (s != null && s.lightSummary() != null && !s.lightSummary().isBlank()) {
            lightSummary = s.lightSummary();
            fallbacks += Boolean.TRUE.equals(s.fallback()) ? 1 : 0;
            version = version != null ? version : s.version();
        } else {
            lightSummary = LightCommentTemplates.summary(items.stream().map(LlmLightComment.Item::stat).toList(),
                    items.stream().map(LlmLightComment.Item::value).toList(),
                    items.stream().map(LlmLightComment.Item::zone).toList(), rng);
            fallbacks++;
            templates++;
        }
        return new LightCommentOutcome(lightSummary, List.copyOf(highlights),
                Math.min(retries.get(), props.maxRetries()), fallbacks, templates, version);
    }

    /** 첫 서버 대기열에 바로 넣고, 실패하면 다른 서버로 1회 — 결과를 못 받으면 null (예외를 던지지 않는다) */
    private <T> CompletableFuture<T> dispatchLightComment(String server, String path, Object body, Class<T> type,
                                                          long endpointDeadlineNanos, AtomicInteger retries) {
        int timeout = lightComment.callTimeoutSeconds();
        CompletableFuture<T> first = enqueue(server, CallKind.LIGHT, () -> postTracked(server, path, body, type, timeout),
                endpointDeadlineNanos);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return await(first);
            } catch (Exception e) {
                if (!isRetryableLightComment(e)) {
                    log.warn("light 코멘트 {} 요청 거절(다시 부르지 않음): {}", path, e.getMessage());
                    return null;
                }
                String other = otherAvailableServer(server);
                if (other == null || !canStartNewAttempt(endpointDeadlineNanos, lightComment.minRemainingSeconds())) {
                    return null;
                }
                retries.incrementAndGet();
                try {
                    return submitToServer(other, CallKind.LIGHT, () -> postTracked(other, path, body, type, timeout),
                            endpointDeadlineNanos);
                } catch (Exception e2) {
                    return null;
                }
            }
        }, lightFanout);
    }

    /** 연결 실패·호출 시간 초과·5xx만 다른 서버로 다시 부른다. 4xx(422 형식 오류·413)는 요청 문제라 같은 결과 */
    private static boolean isRetryableLightComment(Exception e) {
        if (e instanceof WebClientResponseException r) {
            return r.getStatusCode().is5xxServerError();
        }
        return true;
    }

    /** 이 서버가 아닌, 제외 중이 아닌 서버 — 없으면 null */
    private String otherAvailableServer(String server) {
        for (String candidate : props.servers()) {
            if (!candidate.equals(server) && !isExcluded(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 추론 서버의 413 "LLM 입력 길이 초과" — 프롬프트가 모델 입력 한도를 넘은 것이라 같은 요청을 다시 보내도 결과가 같다
     * (LLM 담당 협의 10/7). 재요청하지 않고 끝내며, light는 받은 응답이 없으면 502 LLM_FORMAT_ERROR로 끝난다.
     */
    private static boolean isInputTooLarge(WebClientResponseException e) {
        return e.getStatusCode().value() == 413;
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
                resp = submitToServer(server, CallKind.HEAVY,
                        () -> postTracked(server, props.heavyChapterPath(), request, LlmHeavyChapterResponse.class, props.heavyCallTimeoutSeconds()),
                        endpointDeadlineNanos);
            } catch (WebClientRequestException e) {
                continue; // 연결 실패 — 서버는 postTracked가 제외, 시도 횟수에 넣지 않고 다음 서버로
            } catch (WebClientResponseException e) {
                attempt++;
                if (isInputTooLarge(e)) {
                    break; // 413 입력 길이 초과 — 재요청해도 같으므로 이 챕터는 바로 failed
                }
                continue; // 형식 오류(502) 등 — 재요청
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
                        chapterId, HeavyCommentResponse.STATUS_OK, resp.footnote(), attempt,
                        Boolean.TRUE.equals(resp.fallback()));
            }
            attempt++;
        }
        return new HeavyCommentResponse.ChapterResult(
                chapterId, HeavyCommentResponse.STATUS_FAILED, null, Math.min(attempt, props.maxRetries()));
    }

    /**
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
