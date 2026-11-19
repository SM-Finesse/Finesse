package com.finesse.backend.queue;

import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.QueuedException;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * stats 대기열 — 「전적 검색 대기열 문서」 3장(동작)·6절 SearchQueueService·수집 작업자.
 * <ul>
 *   <li>캐시 미스 검색은 도착 순서로 하나의 줄에 선다. 수집 자리(동시 2건 — StatsService의 공정 세마포어와 같은 것)는 줄 맨 앞부터 준다</li>
 *   <li>작업은 유저마다 하나. 같은 유저를 찾은 사람은 그 작업에 대기표만 붙인다(3.6절)</li>
 *   <li>예상 대기 = (줄에서 내 앞에 기다리는 작업 수 + 1) × 최근 처리 간격. 수집 자리가 비어 있으면 0(3.3절)</li>
 *   <li>예상 대기 ≤ 5초면 요청 안에서 기다리고, 넘으면 202 + 대기표. 요청 안에서 기다리다 실제 대기가 6초를 넘거나
 *       stats 20초 마감에 닿으면 그 자리에서 대기표로 바꾼다(작업·줄 순서는 그대로)</li>
 *   <li>예상 대기가 180초를 넘으면 줄에 넣지 않고 503 SERVER_BUSY(3.4절)</li>
 * </ul>
 * 순서 확인용으로 작업 넣기·시작·끝마다 작업 순번(job#)과 ticket_id를 로그에 남긴다 — 유저명은 남기지 않는다(10절).
 */
public final class SearchQueue {

    private static final Logger log = LoggerFactory.getLogger(SearchQueue.class);

    /** 캐시 미스 수집 한 건 — StatsService.computeInternal (TETR.IO 수집 + calc 계산) */
    @FunctionalInterface
    public interface Collector {
        StatsResponse collect(String normalized);
    }

    /** 수집 성공 직후 — stats 캐시 저장, comment 캐시 비우기, 라이트 미리 만들기 신호 */
    @FunctionalInterface
    public interface Completion {
        /**
         * @param ticketed 이 작업에 대기표가 한 장이라도 발급됐는지 — 라이트 미리 만들기는 "대기표로 수집한 유저"만(4.1절).
         *                 요청 안에서 끝난 사람은 바로 200을 받아 이어서 light를 부르므로 그때 입장 판정을 한다
         */
        void succeeded(String normalized, StatsResponse result, boolean ticketed);
    }

    private enum State { WAITING, RUNNING, DONE, FAILED, CANCELLED }

    private final QueueProperties props;
    private final TicketRegistry registry;
    private final Semaphore slots;
    private final ExecutorService statsExecutor;
    private final int statsSeconds;
    private final int hardWaitSeconds;
    private final Collector collector;
    private final Completion completion;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final List<StatsJob> waiting = new ArrayList<>();
    private final Map<String, StatsJob> jobs = new HashMap<>();
    private int running;
    private long seq;
    private double intervalSeconds;
    private long lastCompletionNanos = Long.MIN_VALUE;
    private long busySinceNanos = Long.MIN_VALUE;

    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stats-queue-timeout");
        t.setDaemon(true);
        return t;
    });
    private final Thread dispatcher;
    private volatile boolean stopped;

    /**
     * @param slots           수집 자리 — StatsService와 같은 공정 세마포어
     * @param statsSeconds    수집 1건 마감(app.endpoint.stats-seconds, 20초) — 시작부터 센다
     * @param hardWaitSeconds 요청 안에서 수집 시작을 기다리는 한도(app.stats.max-queue-wait-seconds, 6초)
     */
    public SearchQueue(QueueProperties props, TicketRegistry registry, Semaphore slots, ExecutorService statsExecutor,
                       int statsSeconds, int hardWaitSeconds, Collector collector, Completion completion,
                       LongSupplier clock) {
        this.props = props;
        this.registry = registry;
        this.slots = slots;
        this.statsExecutor = statsExecutor;
        this.statsSeconds = statsSeconds;
        this.hardWaitSeconds = hardWaitSeconds > 0 ? hardWaitSeconds : props.inlineWaitSeconds() + 1;
        this.collector = collector;
        this.completion = completion;
        this.clock = clock;
        this.intervalSeconds = props.initialIntervalSeconds();
        if (props.testFixedIntervalSeconds() > 0) {
            log.warn("app.queue.test-fixed-interval-seconds={} — 테스트 전용 설정이 켜져 있음(수집 1건을 이 시간으로 맞춤)",
                    props.testFixedIntervalSeconds());
        }
        this.dispatcher = new Thread(this::dispatchLoop, "stats-queue-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public void stop() {
        stopped = true;
        dispatcher.interrupt();
        timeouts.shutdownNow();
    }

    /**
     * 캐시 미스 한 건. 요청 안에서 끝나면 결과를, 대기표로 바뀌면 {@link QueuedException}(202)을,
     * 줄이 가득 찼으면 {@link ServerBusyException}(503)을 던진다. 수집 실패는 원래 예외(404·502) 그대로.
     */
    public StatsResponse submit(String normalized, String ip) {
        long arrived = clock.getAsLong();
        StatsJob job;
        Ticket ticket;
        boolean inline;
        boolean reserved;
        synchronized (lock) {
            job = jobs.get(normalized);
            if (job == null) {
                int est = estimateNewLocked();
                if (est > props.maxWaitSeconds()) {
                    int retryAfter = Math.min(est, props.maxRetryAfterSeconds());
                    log.warn("stats 대기열 상한 — 예상 대기 {}s > {}s, 줄 {}건 · 진행 {}건 → 503 (Retry-After {}s)",
                            est, props.maxWaitSeconds(), waiting.size(), running, retryAfter);
                    throw new ServerBusyException("stats 대기열 상한 초과(예상 대기 " + est + "s)", retryAfter);
                }
                inline = est <= props.inlineWaitSeconds();
                reserved = !inline && registry.reserveIp(ip); // 넘으면 429 — 줄에 넣기 전에
                if (running == 0 && waiting.isEmpty()) {
                    busySinceNanos = arrived; // 비어 있던 시간은 처리 간격에서 뺀다
                }
                job = new StatsJob(++seq, normalized, arrived);
                jobs.put(normalized, job);
                waiting.add(job);
                log.info("stats 대기열 넣기 job#{} (앞 {}건 · 진행 {}건, 예상 대기 {}s, {})",
                        job.seq, waiting.size() - 1, running, est, inline ? "요청 안에서 대기" : "대기표");
                lock.notifyAll();
            } else {
                inline = job.state == State.RUNNING || job.estimateLocked() <= props.inlineWaitSeconds();
                reserved = !inline && registry.reserveIp(ip);
                log.info("stats 대기열 합류 job#{} ({}, 바라보는 대기표 {}개)", job.seq, job.state, job.watchers.size() + 1);
            }
            ticket = registry.newTicket(job, QueueTicketResponse.STAGE_STATS, ip, reserved);
            job.watchers.add(ticket);
        }
        if (!inline) {
            throw issue(job, ticket, "예상 대기 초과");
        }
        return waitInline(job, ticket, arrived);
    }

    private StatsResponse waitInline(StatsJob job, Ticket ticket, long arrived) {
        long startBy = arrived + TimeUnit.SECONDS.toNanos(hardWaitSeconds);
        long deadline = arrived + TimeUnit.SECONDS.toNanos(statsSeconds);
        try {
            job.started.get(Math.max(0, startBy - clock.getAsLong()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // 앞 작업이 늦어져 실제 대기가 6초를 넘음 — 순서는 그대로 두고 대기표로 바꾼다(3.3절, Q15)
            throw issue(job, ticket, "요청 안 대기 " + hardWaitSeconds + "초 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detach(job, ticket);
            throw new TetrioApiException("stats 대기 중단됨", e);
        } catch (ExecutionException e) {
            // started는 예외로 끝나지 않는다
        }
        try {
            StatsResponse result = job.result.get(Math.max(0, deadline - clock.getAsLong()), TimeUnit.NANOSECONDS);
            detach(job, ticket);
            return result;
        } catch (TimeoutException e) {
            // stats 20초 마감 — 502 대신 대기표로(수집은 계속, 끝나면 캐시)
            throw issue(job, ticket, "stats 마감 " + statsSeconds + "초 도달");
        } catch (ExecutionException e) {
            detach(job, ticket);
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new TetrioApiException("stats 계산 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detach(job, ticket);
            throw new TetrioApiException("stats 대기 중단됨", e);
        } catch (java.util.concurrent.CancellationException e) {
            detach(job, ticket);
            throw new TetrioApiException("stats 작업 취소됨", e);
        }
    }

    private QueuedException issue(StatsJob job, Ticket ticket, String reason) {
        synchronized (lock) {
            job.ticketed = true;
        }
        QueueTicketResponse body = registry.publish(ticket);
        log.info("stats 대기표 발급 job#{} ticket={} ({}, position {}, 예상 {}s)",
                job.seq, ticket.id(), reason, body.position(), body.estimatedWaitSeconds());
        return new QueuedException(body);
    }

    private void detach(StatsJob job, Ticket ticket) {
        synchronized (lock) {
            job.watchers.remove(ticket);
        }
    }

    // ---- 수집 작업자 ----

    private void dispatchLoop() {
        while (!stopped) {
            try {
                synchronized (lock) {
                    while (waiting.isEmpty() && !stopped) {
                        lock.wait();
                    }
                }
                if (stopped) {
                    return;
                }
                slots.acquire(); // 자리가 날 때까지 — 줄 맨 앞은 그동안 줄에 그대로 있어 position·취소가 맞다
                StatsJob job;
                synchronized (lock) {
                    job = waiting.isEmpty() ? null : waiting.remove(0);
                    if (job != null) {
                        job.state = State.RUNNING;
                        job.startedNanos = clock.getAsLong();
                        running++;
                    }
                }
                if (job == null) {
                    slots.release(); // 기다리던 작업이 그사이 취소됨
                    continue;
                }
                job.started.complete(null);
                log.info("stats 수집 시작 job#{} (줄 대기 {}ms, 진행 {}건) ticket={}", job.seq,
                        TimeUnit.NANOSECONDS.toMillis(job.startedNanos - job.enqueuedNanos), running, issuedIds(job));
                start(job);
            } catch (InterruptedException e) {
                if (stopped) {
                    return;
                }
            } catch (RuntimeException e) {
                log.error("stats 대기열 작업자 오류", e);
            }
        }
    }

    private void start(StatsJob job) {
        long minNanos = TimeUnit.SECONDS.toNanos(Math.max(0, props.testFixedIntervalSeconds()));
        FutureTask<StatsResponse> task = new FutureTask<>(() -> {
            StatsResponse r = collector.collect(job.username);
            long rest = job.startedNanos + minNanos - clock.getAsLong();
            if (rest > 0) {
                TimeUnit.NANOSECONDS.sleep(rest); // 테스트 전용 — 수집 1건을 고정 시간으로
            }
            return r;
        });
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                finish(job, task);
            }
        };
        try {
            statsExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    // 자리는 수집이 "실제로 끝났을 때" 반납 — 마감으로 먼저 실패 처리해도 calc 안의 수집은 계속 TETR.IO를 부를 수 있다
                    slots.release();
                }
                finish.run();
            });
        } catch (RejectedExecutionException e) {
            slots.release();
            task.cancel(false);
            finish.run();
            return;
        }
        timeouts.schedule(() -> {
            if (!task.isDone()) {
                task.cancel(true);
                finish.run();
            }
        }, statsSeconds, TimeUnit.SECONDS);
    }

    private void finish(StatsJob job, FutureTask<StatsResponse> task) {
        StatsResponse result = null;
        RuntimeException failure = null;
        if (task.isCancelled()) {
            failure = new TetrioApiException("stats 수집 마감(" + statsSeconds + "s) 초과 job#" + job.seq, null);
        } else {
            try {
                result = task.get();
            } catch (ExecutionException e) {
                failure = e.getCause() instanceof RuntimeException re ? re : new TetrioApiException("stats 계산 실패", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new TetrioApiException("stats 계산 중단됨", e);
            }
        }
        if (result != null) {
            boolean ticketed;
            synchronized (lock) {
                ticketed = job.ticketed;
            }
            try {
                completion.succeeded(job.username, result, ticketed); // 캐시에 먼저 넣고 나서 작업을 지운다 — 사이에 온 요청은 캐시 히트
            } catch (RuntimeException e) {
                log.warn("stats 수집 후처리 실패 job#{}", job.seq, e);
            }
        }
        long now = clock.getAsLong();
        List<Ticket> watchers;
        synchronized (lock) {
            jobs.remove(job.username, job);
            running--;
            job.state = result != null ? State.DONE : State.FAILED;
            updateIntervalLocked(now);
            watchers = List.copyOf(job.watchers);
        }
        if (result != null) {
            job.result.complete(result);
        } else {
            job.result.completeExceptionally(failure);
        }
        String code = result != null ? null : errorCode(failure);
        for (Ticket t : watchers) {
            t.finish(result != null, code, now);
        }
        log.info("stats 수집 끝 job#{} {} ({}ms, 처리 간격 {}s) ticket={}", job.seq,
                result != null ? "ok" : "failed " + code, TimeUnit.NANOSECONDS.toMillis(now - job.startedNanos),
                String.format("%.1f", interval()), watchers.stream().filter(t -> t.issued).map(Ticket::id).toList());
    }

    /** 순서 확인 로그용 — 이 작업을 바라보는 발급된 대기표 id (요청 안에서 기다리는 사람은 없음) */
    private List<String> issuedIds(StatsJob job) {
        synchronized (lock) {
            return job.watchers.stream().filter(t -> t.issued).map(Ticket::id).toList();
        }
    }

    /** 대기표 failed의 error_code — 원래 stats가 냈을 오류 */
    static String errorCode(RuntimeException failure) {
        return failure instanceof UserNotFoundException ? "USER_NOT_FOUND" : "TETRIO_API_UNAVAILABLE";
    }

    /** 처리 간격 = 수집 작업이 하나 끝날 때마다 잰 완료 간격(직전 완료부터, 줄이 비어 있던 시간은 뺌)의 EWMA */
    private void updateIntervalLocked(long now) {
        long from = Math.max(lastCompletionNanos, busySinceNanos);
        if (from != Long.MIN_VALUE && now > from) {
            double sample = (now - from) / 1e9;
            double a = props.intervalAlpha();
            intervalSeconds = a * sample + (1 - a) * intervalSeconds;
        }
        lastCompletionNanos = now;
        if (running == 0 && waiting.isEmpty()) {
            busySinceNanos = Long.MIN_VALUE;
            lastCompletionNanos = Long.MIN_VALUE; // 다음 작업은 새 바쁜 구간의 시작부터 잰다
        }
    }

    private double interval() {
        return props.testFixedIntervalSeconds() > 0 ? props.testFixedIntervalSeconds() : intervalSeconds;
    }

    /**
     * 새 작업의 예상 대기 — 동시 처리 수(2)로 나누지 않는다. TETR.IO 호출은 서버 전체 1초에 1회라 두 작업이 호출 몫을
     * 나눠 쓰므로, 완료 간격을 직접 잰 값에 동시 처리 수와 호출 제한이 함께 반영돼 있다(3.3절).
     */
    private int estimateNewLocked() {
        if (waiting.isEmpty() && slots.availablePermits() > 0) {
            return 0;
        }
        return (int) Math.ceil((waiting.size() + 1) * interval());
    }

    // ---- 확인용 ----

    public int waitingCount() {
        synchronized (lock) {
            return waiting.size();
        }
    }

    public double intervalSeconds() {
        synchronized (lock) {
            return interval();
        }
    }

    private final class StatsJob implements QueueJob {
        final long seq;
        final String username;
        final long enqueuedNanos;
        final List<Ticket> watchers = new ArrayList<>();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<StatsResponse> result = new CompletableFuture<>();
        State state = State.WAITING;
        long startedNanos;
        boolean ticketed; // 대기표가 한 장이라도 발급됨

        StatsJob(long seq, String username, long enqueuedNanos) {
            this.seq = seq;
            this.username = username;
            this.enqueuedNanos = enqueuedNanos;
        }

        @Override
        public String stage() {
            return QueueTicketResponse.STAGE_STATS;
        }

        @Override
        public boolean isRunning() {
            synchronized (lock) {
                return state != State.WAITING;
            }
        }

        /** 내 앞 작업 수(지금 수집 중인 작업 포함) + 1 — 같은 유저를 기다리는 사람들은 한 작업으로 셈(5.1절) */
        @Override
        public int position() {
            synchronized (lock) {
                int idx = waiting.indexOf(this);
                return idx < 0 ? 1 : idx + running + 1;
            }
        }

        @Override
        public int estimatedWaitSeconds() {
            synchronized (lock) {
                return estimateLocked();
            }
        }

        int estimateLocked() {
            if (state == State.RUNNING) {
                double elapsed = (clock.getAsLong() - startedNanos) / 1e9;
                return (int) Math.max(1, Math.ceil(interval() - elapsed));
            }
            if (state != State.WAITING) {
                return 0;
            }
            int idx = waiting.indexOf(this);
            if (idx == 0 && slots.availablePermits() > 0) {
                return 0;
            }
            return (int) Math.ceil((Math.max(0, idx) + 1) * interval());
        }

        @Override
        public void detach(Ticket ticket) {
            synchronized (lock) {
                watchers.remove(ticket);
                if (watchers.isEmpty() && state == State.WAITING) {
                    waiting.remove(this);
                    jobs.remove(username, this);
                    state = State.CANCELLED;
                    result.cancel(false);
                    log.info("stats 대기열에서 빠짐 job#{} (바라보는 대기표 없음, 시작 전)", seq);
                    if (running == 0 && waiting.isEmpty()) {
                        busySinceNanos = Long.MIN_VALUE;
                        lastCompletionNanos = Long.MIN_VALUE;
                    }
                }
            }
        }
    }
}
