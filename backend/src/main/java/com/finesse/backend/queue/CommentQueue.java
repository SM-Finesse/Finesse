package com.finesse.backend.queue;

import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.QueueTicketResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * LLM 입장 판정과 코멘트 대기표 — 「전적 검색 대기열 문서」 4.2·4.3절.
 * <ul>
 *   <li>코멘트 결과가 없으면 LLM 예상 대기를 계산해, light 15초·heavy 30초 이하면 지금처럼 요청 안에서 만든다(inline)</li>
 *   <li>넘으면 작업을 이 줄에 "보류"하고 대기표(stage comment·heavy)를 준다. 보류한 작업은 도착 순서대로,
 *       LLM 예상 대기가 기준 아래로 내려오면 시작한다 — 그래서 대기표로 기다린 시간은 light 40초·heavy 60초 마감에 들어가지 않는다</li>
 *   <li>보류 중인 작업이 있으면 새 요청은 기준과 상관없이 그 뒤에 선다 — 라이트·헤비 구분 없이 들어온 순서대로(4.3절)</li>
 *   <li>LLM 예상 대기 = LLM 대기열에서 내 앞 작업들의 예상 처리 시간 합 ÷ 지금 배정할 수 있는 LLM 서버 수.
 *       배정할 수 있는 서버가 0대면 입장 판정 없이 지금처럼(문장 틀)</li>
 *   <li>같은 유저의 같은 종류 작업은 하나 — 생성 중에 또 오면 새로 만들지 않는다(4.1절)</li>
 * </ul>
 */
public final class CommentQueue {

    private static final Logger log = LoggerFactory.getLogger(CommentQueue.class);
    private static final long TICK_MILLIS = 500;

    public enum Kind {
        LIGHT(QueueTicketResponse.STAGE_COMMENT), HEAVY(QueueTicketResponse.STAGE_HEAVY);

        final String stage;

        Kind(String stage) {
            this.stage = stage;
        }
    }

    /** LLM 대기열의 지금 부하 — LlmClient가 구현한다 */
    public interface LlmLoad {
        /** 연결 실패로 제외된 서버를 뺀, 지금 배정할 수 있는 서버 수 */
        int assignableServers();

        /** 서버별 대기열에 들어가 있는(진행 중 포함) 서비스 호출들의 예상 처리 시간 합(초) */
        double pendingWorkSeconds();

        /** 이 종류 작업 하나의 예상 처리 시간 합(초) — light 4회, heavy 8챕터 */
        double jobWorkSeconds(Kind kind);

        /** 이 종류 작업 하나가 시작부터 끝날 때까지 걸리는 예상 시간(초) — running 대기표의 남은 시간 계산용 */
        double jobDurationSeconds(Kind kind);
    }

    /** 입장 판정 결과 */
    public sealed interface Admission {
        /** 요청 안에서 만든다 — 끝나면 {@link CommentQueue#inlineFinished()}를 부를 것 */
        record Inline() implements Admission {
        }

        /** 같은 유저의 작업이 이미 생성 중 — 요청 안에서 그 결과를 기다린다(light) */
        record Join(Job job) implements Admission {
        }

        /** 대기표(202 · event: queued) */
        record Queued(Job job, Ticket ticket) implements Admission {
        }
    }

    private enum State { HELD, RUNNING, DONE, FAILED, CANCELLED }

    private final QueueProperties props;
    private final TicketRegistry registry;
    private final LlmLoad load;
    private final LongSupplier clock;
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker;

    private final Object lock = new Object();
    private final List<Job> held = new ArrayList<>();
    private final Map<Kind, Map<String, Job>> jobs = Map.of(Kind.LIGHT, new HashMap<>(), Kind.HEAVY, new HashMap<>());
    private int runningInline;
    private int runningBackground;
    private long seq;

    public CommentQueue(QueueProperties props, TicketRegistry registry, LlmLoad load, LongSupplier clock, boolean startTicker) {
        this.props = props;
        this.registry = registry;
        this.load = load;
        this.clock = clock;
        if (startTicker) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "comment-queue-dispatcher");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(this::dispatchSafely, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            ticker = null;
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        runner.shutdownNow();
    }

    /**
     * 요청 하나의 입장 판정. 대기표를 줄 때는 IP 상한을 먼저 본다(429).
     *
     * @param waitIfRunning true면(light) 같은 유저 작업이 생성 중일 때 요청 안에서 기다린다. false면(heavy) 대기표를 붙인다 —
     *                      스트림을 다른 요청이 만드는 생성에 이어 붙일 수 없기 때문
     * @param work          보류했다가 차례가 오면 돌릴 생성 작업(캐시·준비 저장까지)
     */
    public Admission admit(Kind kind, String username, boolean waitIfRunning, Runnable work, String ip) {
        Admission result;
        synchronized (lock) {
            Job job = jobs.get(kind).get(username);
            if (job != null) {
                if (job.state == State.RUNNING && waitIfRunning) {
                    return new Admission.Join(job);
                }
                boolean reserved = registry.reserveIp(ip);
                Ticket t = registry.newTicket(job, kind.stage, ip, reserved);
                job.watchers.add(t);
                log.info("{} 작업 합류 job#{} ({}) ticket={}", kind, job.seq, job.state, t.id());
                return new Admission.Queued(job, t);
            }
            int servers = load.assignableServers();
            double est = estimateLocked(0);
            if (held.isEmpty() && (servers == 0 || est <= threshold(kind))) {
                runningInline++;
                log.info("{} 요청 안에서 생성 (LLM 예상 대기 {}s ≤ {}s, 작업 1건 처리 {}s)", kind, Math.round(est), threshold(kind),
                        Math.round(load.jobWorkSeconds(kind)));
                return new Admission.Inline();
            }
            boolean reserved = registry.reserveIp(ip); // 넘으면 429 — 줄에 넣기 전에
            job = holdLocked(kind, username, work);
            Ticket t = registry.newTicket(job, kind.stage, ip, reserved);
            job.watchers.add(t);
            log.info("{} 입장 대기 job#{} (LLM 예상 대기 {}s > {}s 또는 앞에 보류 {}건) ticket={}",
                    kind, job.seq, Math.round(est), threshold(kind), held.size() - 1, t.id());
            result = new Admission.Queued(job, t);
        }
        dispatch();
        return result;
    }

    /**
     * 라이트 미리 만들기(4.1절) — 대기표 없이 작업만. 이미 작업이 있으면 아무것도 하지 않는다.
     * 기준 아래면 바로 시작하고, 넘으면 보류했다가 차례대로 시작한다.
     */
    public void prefetch(Kind kind, String username, Runnable work) {
        synchronized (lock) {
            if (jobs.get(kind).containsKey(username)) {
                return;
            }
            Job job = holdLocked(kind, username, work);
            job.prefetch = true;
            log.info("{} 미리 만들기 job#{} (앞에 보류 {}건)", kind, job.seq, held.size() - 1);
        }
        dispatch();
    }

    /** 요청 안에서 만든 생성이 끝남 — position 계산용 진행 수를 줄인다 */
    public void inlineFinished() {
        synchronized (lock) {
            runningInline = Math.max(0, runningInline - 1);
        }
        dispatch();
    }

    private Job holdLocked(Kind kind, String username, Runnable work) {
        Job job = new Job(++seq, kind, username, work);
        held.add(job);
        jobs.get(kind).put(username, job);
        return job;
    }

    private int threshold(Kind kind) {
        return kind == Kind.LIGHT ? props.lightInlineWaitSeconds() : props.heavyInlineWaitSeconds();
    }

    /** 보류 줄의 앞에서 heldAhead개를 더한 LLM 예상 대기(초) */
    private double estimateLocked(int heldAhead) {
        double work = load.pendingWorkSeconds();
        for (int i = 0; i < heldAhead && i < held.size(); i++) {
            work += load.jobWorkSeconds(held.get(i).kind);
        }
        return work / Math.max(1, load.assignableServers());
    }

    /** 보류 줄 맨 앞부터, LLM 예상 대기가 그 작업의 기준 아래면 시작 — 도착 순서를 지킨다 */
    public void dispatch() {
        Job start = null;
        double startEst = 0;
        synchronized (lock) {
            if (!held.isEmpty()) {
                Job head = held.get(0);
                int servers = load.assignableServers();
                // 한 번에 하나씩 — 시작한 작업의 호출이 LLM 대기열에 들어간 뒤(다음 판정 때) 그 몫이 예상 대기에 잡힌다
                double est = estimateLocked(0);
                if (servers == 0 || est <= threshold(head.kind)) {
                    startEst = est;
                    held.remove(0);
                    head.state = State.RUNNING;
                    head.startedNanos = clock.getAsLong();
                    runningBackground++;
                    start = head;
                }
            }
        }
        if (start != null) {
            Job job = start;
            List<String> ids;
            synchronized (lock) {
                ids = job.watchers.stream().map(Ticket::id).toList();
            }
            log.info("{} 생성 시작 job#{} (보류 {}ms{}, LLM 예상 대기 {}s, 작업 1건 처리 {}s) ticket={}", job.kind, job.seq,
                    TimeUnit.NANOSECONDS.toMillis(job.startedNanos - job.createdNanos), job.prefetch ? ", 미리 만들기" : "",
                    Math.round(startEst), Math.round(load.jobWorkSeconds(job.kind)), ids);
            CompletableFuture.runAsync(job.work, runner).whenComplete((v, e) -> finish(job, e));
        }
    }

    private void dispatchSafely() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            log.warn("코멘트 대기열 작업자 오류", e);
        }
    }

    private void finish(Job job, Throwable error) {
        long now = clock.getAsLong();
        List<Ticket> watchers;
        synchronized (lock) {
            job.state = error == null ? State.DONE : State.FAILED;
            runningBackground = Math.max(0, runningBackground - 1);
            jobs.get(job.kind).remove(job.username, job);
            watchers = List.copyOf(job.watchers);
        }
        if (error == null) {
            job.done.complete(null);
        } else {
            log.warn("{} 생성 실패 job#{}", job.kind, job.seq, error);
            job.done.completeExceptionally(error);
        }
        for (Ticket t : watchers) {
            t.finish(error == null, "INTERNAL_ERROR", now);
        }
        log.info("{} 생성 끝 job#{} ({}ms) ticket={}", job.kind, job.seq,
                TimeUnit.NANOSECONDS.toMillis(now - job.startedNanos), watchers.stream().map(Ticket::id).toList());
        dispatch();
    }

    // ---- 확인용 ----

    public int heldCount() {
        synchronized (lock) {
            return held.size();
        }
    }

    public final class Job implements QueueJob {
        final long seq;
        final Kind kind;
        final String username;
        final Runnable work;
        final long createdNanos;
        final List<Ticket> watchers = new ArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        State state = State.HELD;
        long startedNanos;
        boolean prefetch;

        Job(long seq, Kind kind, String username, Runnable work) {
            this.seq = seq;
            this.kind = kind;
            this.username = username;
            this.work = work;
            this.createdNanos = clock.getAsLong();
        }

        /** 생성이 끝날 때까지 — light 합류(Join)용 */
        public CompletableFuture<Void> done() {
            return done;
        }

        @Override
        public String stage() {
            return kind.stage;
        }

        @Override
        public boolean isRunning() {
            synchronized (lock) {
                return state != State.HELD;
            }
        }

        /** 보류 중 — 내 앞 보류 작업 수 + 지금 생성 중인 작업 수(요청 안 생성 포함) + 1 */
        @Override
        public int position() {
            synchronized (lock) {
                int idx = held.indexOf(this);
                return idx < 0 ? 1 : idx + runningInline + runningBackground + 1;
            }
        }

        @Override
        public int estimatedWaitSeconds() {
            synchronized (lock) {
                if (state == State.RUNNING) {
                    double elapsed = (clock.getAsLong() - startedNanos) / 1e9;
                    return (int) Math.max(1, Math.ceil(load.jobDurationSeconds(kind) - elapsed));
                }
                if (state != State.HELD) {
                    return 0;
                }
                // LLM 예상 대기 = 내 앞 작업들(대기열에 들어간 호출 + 앞에 보류된 작업)의 예상 처리 시간 합 ÷ 서버 수
                int idx = Math.max(0, held.indexOf(this));
                return (int) Math.max(1, Math.ceil(estimateLocked(idx)));
            }
        }

        @Override
        public void detach(Ticket ticket) {
            synchronized (lock) {
                boolean had = watchers.remove(ticket);
                if (had && watchers.isEmpty() && state == State.HELD) {
                    held.remove(this);
                    jobs.get(kind).remove(username, this);
                    state = State.CANCELLED;
                    log.info("{} 보류 작업 빠짐 job#{} (바라보는 대기표 없음, 시작 전)", kind, seq);
                }
            }
        }
    }
}
