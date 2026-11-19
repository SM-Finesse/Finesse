package com.finesse.backend.queue;

import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.QueuedException;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TooManyTicketsException;
import com.finesse.backend.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * stats 대기열 — 「전적 검색 대기열 문서」 3장 동작과 10절 QA 시나리오(Q1·Q3·Q4·Q5·Q9·Q11·Q12·Q15·Q16).
 * 수집은 유저마다 따로 풀어 주는 가짜 collector로 대신한다(TETR.IO를 부르지 않음).
 */
class SearchQueueTest {

    private final ExecutorService statsExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private final AtomicLong fakeNow = new AtomicLong(1_000_000_000L);
    private SearchQueue queue;

    /** 유저마다 풀어 줄 때까지 멈추는 수집 — 시작 순서를 기록한다 */
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final List<String> started = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> collectCount = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

    @AfterEach
    void shutdown() {
        gates.values().forEach(CountDownLatch::countDown);
        if (queue != null) {
            queue.stop();
        }
        statsExecutor.shutdownNow();
        callers.shutdownNow();
    }

    private static QueueProperties props(double interval, int maxWait, int maxTicketsPerIp) {
        return new QueueProperties(true, 5, maxWait, 30, 2, true, 15, 30, maxTicketsPerIp, 5, 120, interval, 0.3, 60, 60, 0, 1.5, 3.5);
    }

    private TicketRegistry registry(QueueProperties p) {
        return new TicketRegistry(p, System::nanoTime, false);
    }

    private SearchQueue queue(QueueProperties p, TicketRegistry registry, int slots, int hardWaitSeconds) {
        queue = new SearchQueue(p, registry, new Semaphore(slots, true), statsExecutor, 20, hardWaitSeconds,
                this::collect, (u, r, t) -> { }, System::nanoTime);
        return queue;
    }

    private StatsResponse collect(String user) {
        started.add(user);
        collectCount.computeIfAbsent(user, k -> new AtomicInteger()).incrementAndGet();
        try {
            gate(user).await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        RuntimeException f = failures.get(user);
        if (f != null) {
            throw f;
        }
        return new StatsResponse(user, true, 0, Instant.now(), null, null, null, null, null, Map.of(), null);
    }

    private CountDownLatch gate(String user) {
        return gates.computeIfAbsent(user, k -> new CountDownLatch(1));
    }

    private void release(String user) {
        gate(user).countDown();
    }

    /** 다른 스레드에서 submit — 끝나면 결과 또는 던진 예외 */
    private Future<Object> submitAsync(String user, String ip) {
        return callers.submit(() -> {
            try {
                return queue.submit(user, ip);
            } catch (RuntimeException e) {
                return e;
            }
        });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("조건을 기다리다 시간 초과");
            }
            Thread.sleep(10);
        }
    }

    private static QueueTicketResponse ticketOf(Object outcome) {
        assertThat(outcome).isInstanceOf(QueuedException.class);
        return ((QueuedException) outcome).ticket();
    }

    @Test
    void Q1_새_유저_20명_동시_검색_503_없이_도착_순서대로_수집하고_7절_예시대로_대기표() throws Exception {
        QueueProperties p = props(5, 180, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 2, 6);

        Future<?>[] calls = new Future<?>[20];
        for (int i = 1; i <= 20; i++) {
            String user = "u" + i;
            int expectedInQueue = i;
            calls[i - 1] = submitAsync(user, "10.0.0." + i);
            // 줄에 들어간 뒤 다음 사람 — 도착 순서를 고정한다
            await(() -> started.size() + queue.waitingCount() >= expectedInQueue);
        }
        // 4번째부터는 대기표(즉시 반환). 20번째는 "앞에 19명 · 약 90초"
        for (int i = 4; i <= 20; i++) {
            QueueTicketResponse t = ticketOf(calls[i - 1].get(5, TimeUnit.SECONDS));
            assertThat(t.position()).as("u" + i + " position").isEqualTo(i);
            assertThat(t.estimatedWaitSeconds()).as("u" + i + " 예상 대기").isEqualTo((i - 2) * 5);
            assertThat(t.stage()).isEqualTo(QueueTicketResponse.STAGE_STATS);
            assertThat(t.ticketId()).startsWith("q_").doesNotContain("u" + i);
        }
        // 차례대로 풀어 주면 수집이 시작되는 순서 = 요청 순서
        for (int i = 1; i <= 20; i++) {
            String user = "u" + i;
            await(() -> started.contains(user));
            release(user);
        }
        await(() -> started.size() == 20);
        assertThat(started).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> "u" + i).toList());
        // 앞의 3명은 요청 안에서 200(3번째는 예상 대기 5초라 요청 안에서 기다림)
        for (int i = 1; i <= 3; i++) {
            assertThat(calls[i - 1].get(5, TimeUnit.SECONDS)).as("u" + i).isInstanceOf(StatsResponse.class);
        }
    }

    @Test
    void Q15_요청_안에서_기다리다_실제_대기가_한도를_넘으면_대기표로_바뀌고_순서는_그대로() throws Exception {
        QueueProperties p = props(5, 180, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 1); // 요청 안 대기 한도 1초(운영은 6초)

        Future<Object> first = submitAsync("a", "ip");
        await(() -> started.contains("a"));
        Future<Object> second = submitAsync("b", "ip"); // 예상 대기 5초 → 요청 안에서 기다림

        QueueTicketResponse t = ticketOf(second.get(5, TimeUnit.SECONDS)); // 1초 뒤 대기표로
        assertThat(t.position()).isEqualTo(2);
        assertThat(registry.view(t.ticketId()).status()).isEqualTo(QueueTicketResponse.STATUS_WAITING);

        release("a");
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(StatsResponse.class);
        await(() -> started.contains("b"));
        release("b");
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(t.ticketId()).status()));
        assertThat(collectCount.get("b").get()).isEqualTo(1);
    }

    @Test
    void Q3_같은_유저는_대기표_따로_작업_하나_수집_한_번() throws Exception {
        QueueProperties p = props(10, 180, 0); // 처리 간격 10초 — 줄 둘째부터 대기표
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);

        submitAsync("busy", "ip0");
        await(() -> started.contains("busy"));
        QueueTicketResponse t1 = ticketOf(submitAsync("same", "ip1").get(5, TimeUnit.SECONDS));
        QueueTicketResponse t2 = ticketOf(submitAsync("same", "ip2").get(5, TimeUnit.SECONDS));

        assertThat(t1.ticketId()).isNotEqualTo(t2.ticketId());
        assertThat(t1.position()).isEqualTo(t2.position()).isEqualTo(2);

        release("busy");
        await(() -> started.contains("same"));
        release("same");
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(t1.ticketId()).status())
                && QueueTicketResponse.STATUS_DONE.equals(registry.view(t2.ticketId()).status()));
        assertThat(collectCount.get("same").get()).isEqualTo(1);
    }

    @Test
    void Q4_Q11_취소하면_시작_전_작업은_줄에서_빠지고_뒤_사람이_당겨진다_남은_대기표가_있으면_작업은_남는다() throws Exception {
        QueueProperties p = props(10, 180, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);

        submitAsync("busy", "ip0");
        await(() -> started.contains("busy"));
        QueueTicketResponse a1 = ticketOf(submitAsync("a", "ip1").get(5, TimeUnit.SECONDS));
        QueueTicketResponse a2 = ticketOf(submitAsync("a", "ip2").get(5, TimeUnit.SECONDS));
        QueueTicketResponse b = ticketOf(submitAsync("b", "ip3").get(5, TimeUnit.SECONDS));
        assertThat(registry.view(b.ticketId()).position()).isEqualTo(3);

        registry.cancel(a1.ticketId()); // a는 a2가 아직 바라봄 — 작업은 남는다
        assertThat(registry.view(b.ticketId()).position()).isEqualTo(3);
        registry.cancel(a2.ticketId()); // 둘 다 취소 — 시작 전인 a는 줄에서 빠진다
        assertThat(registry.view(b.ticketId()).position()).isEqualTo(2);
        assertThatThrownBy(() -> registry.view(a1.ticketId())).hasMessageContaining("다시 검색");

        release("busy");
        await(() -> started.contains("b"));
        release("b");
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(b.ticketId()).status()));
        assertThat(started).doesNotContain("a");
    }

    @Test
    void Q4_30초_동안_묻지_않은_대기표는_버리고_끝난_대기표는_2분_뒤_지운다() throws Exception {
        QueueProperties p = props(10, 180, 0);
        TicketRegistry registry = new TicketRegistry(p, fakeNow::get, false);
        // 운영과 같이 대기열과 레지스트리가 같은 시계를 쓴다(StatsService가 ticketRegistry::now를 넘김)
        queue = new SearchQueue(p, registry, new Semaphore(1, true), statsExecutor, 20, 6,
                this::collect, (u, r, t) -> { }, fakeNow::get);

        submitAsync("busy", "ip0");
        await(() -> started.contains("busy"));
        QueueTicketResponse left = ticketOf(submitAsync("left", "ip1").get(5, TimeUnit.SECONDS));
        QueueTicketResponse stay = ticketOf(submitAsync("stay", "ip2").get(5, TimeUnit.SECONDS));

        fakeNow.addAndGet(TimeUnit.SECONDS.toNanos(20));
        registry.view(stay.ticketId()); // stay는 계속 묻는다
        fakeNow.addAndGet(TimeUnit.SECONDS.toNanos(11));
        registry.cleanup();

        assertThatThrownBy(() -> registry.view(left.ticketId())).hasMessageContaining("다시 검색");
        assertThat(registry.view(stay.ticketId()).position()).as("떠난 사람 자리만큼 당겨짐").isEqualTo(2);

        release("busy");
        await(() -> started.contains("stay"));
        release("stay");
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(stay.ticketId()).status()));
        // Q12 — 끝난 대기표는 1분 뒤에 물어도 done, 2분이 지나면 지운다
        fakeNow.addAndGet(TimeUnit.SECONDS.toNanos(60));
        registry.cleanup();
        assertThat(registry.view(stay.ticketId()).status()).isEqualTo(QueueTicketResponse.STATUS_DONE);
        fakeNow.addAndGet(TimeUnit.SECONDS.toNanos(121));
        registry.cleanup();
        assertThat(registry.size()).isZero();
        assertThat(started).doesNotContain("left");
    }

    @Test
    void Q5_줄_예상_대기가_상한을_넘으면_503_Retry_After는_예상_대기_최대_60초() throws Exception {
        QueueProperties p = props(40, 100, 0); // 처리 간격 40초, 상한 100초
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);

        submitAsync("busy", "ip");
        await(() -> started.contains("busy"));
        ticketOf(submitAsync("w1", "ip").get(5, TimeUnit.SECONDS)); // 예상 40초
        ticketOf(submitAsync("w2", "ip").get(5, TimeUnit.SECONDS)); // 예상 80초

        Object third = submitAsync("w3", "ip").get(5, TimeUnit.SECONDS); // 예상 120초 > 100초
        assertThat(third).isInstanceOf(ServerBusyException.class);
        assertThat(((ServerBusyException) third).retryAfterSeconds()).isEqualTo(60);
        assertThat(queue.waitingCount()).isEqualTo(2);
    }

    @Test
    void Q9_수집_실패한_유저의_대기표는_failed와_원래_오류_코드() throws Exception {
        QueueProperties p = props(10, 180, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);
        failures.put("ghost", new UserNotFoundException("ghost"));

        submitAsync("busy", "ip0");
        await(() -> started.contains("busy"));
        QueueTicketResponse t = ticketOf(submitAsync("ghost", "ip1").get(5, TimeUnit.SECONDS));
        release("busy");
        await(() -> started.contains("ghost"));
        release("ghost");
        await(() -> QueueTicketResponse.STATUS_FAILED.equals(registry.view(t.ticketId()).status()));
        assertThat(registry.view(t.ticketId()).errorCode()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void Q16_같은_IP의_4번째_대기표는_429_다른_IP는_통과_0이면_제한_없음() throws Exception {
        QueueProperties p = props(10, 500, 3);
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);

        submitAsync("busy", "other");
        await(() -> started.contains("busy"));
        for (int i = 1; i <= 3; i++) {
            ticketOf(submitAsync("u" + i, "1.2.3.4").get(5, TimeUnit.SECONDS));
        }
        assertThat(submitAsync("u4", "1.2.3.4").get(5, TimeUnit.SECONDS)).isInstanceOf(TooManyTicketsException.class);
        assertThat(submitAsync("u5", "5.6.7.8").get(5, TimeUnit.SECONDS)).isInstanceOf(QueuedException.class);

        QueueProperties off = props(10, 500, 0);
        TicketRegistry unlimited = registry(off);
        for (int i = 0; i < 5; i++) {
            boolean reserved = unlimited.reserveIp("1.2.3.4");
            unlimited.publish(unlimited.newTicket(new FixedJob(), QueueTicketResponse.STAGE_STATS, "1.2.3.4", reserved));
        }
        assertThat(unlimited.size()).isEqualTo(5);
    }

    @Test
    void Q16_같은_IP_요청이_동시에_몰려도_대기표는_상한을_넘지_않는다() throws Exception {
        TicketRegistry registry = registry(props(10, 500, 3));
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> all = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(callers.submit(() -> {
                go.await();
                try {
                    boolean reserved = registry.reserveIp("1.2.3.4");
                    registry.publish(registry.newTicket(new FixedJob(), QueueTicketResponse.STAGE_STATS, "1.2.3.4", reserved));
                    issued.incrementAndGet();
                } catch (TooManyTicketsException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertThat(issued.get()).isEqualTo(3);
        assertThat(rejected.get()).isEqualTo(17);
    }

    @Test
    void Q2_대기표_상태를_반복_조회하면_position은_줄어들기만_하고_차례가_오면_running_다음_done() throws Exception {
        QueueProperties p = props(10, 500, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 1, 6);

        submitAsync("busy", "ip");
        await(() -> started.contains("busy"));
        for (int i = 1; i <= 4; i++) {
            ticketOf(submitAsync("w" + i, "ip").get(5, TimeUnit.SECONDS));
        }
        QueueTicketResponse last = ticketOf(submitAsync("last", "ip").get(5, TimeUnit.SECONDS));
        int previous = registry.view(last.ticketId()).position();
        assertThat(previous).isEqualTo(6);

        List<String> order = List.of("busy", "w1", "w2", "w3", "w4");
        for (String user : order) {
            await(() -> started.contains(user));
            release(user);
            await(() -> !QueueTicketResponse.STATUS_WAITING.equals(registry.view(last.ticketId()).status())
                    || registry.view(last.ticketId()).position() < 6 - order.indexOf(user));
            QueueTicketResponse now = registry.view(last.ticketId());
            if (QueueTicketResponse.STATUS_WAITING.equals(now.status())) {
                assertThat(now.position()).isLessThan(previous);
                previous = now.position();
            }
        }
        await(() -> started.contains("last"));
        QueueTicketResponse running = registry.view(last.ticketId());
        assertThat(running.status()).isEqualTo(QueueTicketResponse.STATUS_RUNNING);
        assertThat(running.position()).as("running이면 position 생략").isNull();
        assertThat(running.estimatedWaitSeconds()).isNotNull();
        release("last");
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(last.ticketId()).status()));
    }

    @Test
    void 요청_안에서_끝나면_대기표를_발급하지_않는다() throws Exception {
        QueueProperties p = props(5, 180, 0);
        TicketRegistry registry = registry(p);
        queue(p, registry, 2, 6);
        release("solo");

        Object r = submitAsync("solo", "ip").get(5, TimeUnit.SECONDS);
        assertThat(r).isInstanceOf(StatsResponse.class);
        assertThat(registry.size()).isZero();
    }

    @Test
    void 대기표_id는_유저명을_담지_않는_임의_값() {
        TicketRegistry registry = registry(props(5, 180, 0));
        Ticket a = registry.newTicket(new FixedJob(), QueueTicketResponse.STAGE_STATS, null);
        Ticket b = registry.newTicket(new FixedJob(), QueueTicketResponse.STAGE_STATS, null);
        assertThat(a.id()).matches("q_[0-9a-f]{24}").isNotEqualTo(b.id());
        Throwable t = catchThrowable(() -> registry.view("q_nope"));
        assertThat(t).hasMessage("대기 시간이 지났습니다, 다시 검색해 주세요");
    }

    @Test
    void poll_after는_보통_2초_줄이_길면_최대_5초() {
        TicketRegistry registry = registry(props(5, 180, 0));
        assertThat(registry.pollAfter(10)).isEqualTo(2);
        assertThat(registry.pollAfter(50)).isEqualTo(3);
        assertThat(registry.pollAfter(170)).isEqualTo(5);
        assertThat(registry.pollAfter(null)).isEqualTo(2);
    }

    /** 대기 중인 채로 있는 작업 — 레지스트리 단독 확인용 */
    private static final class FixedJob implements QueueJob {
        @Override
        public String stage() {
            return QueueTicketResponse.STAGE_STATS;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public int position() {
            return 1;
        }

        @Override
        public int estimatedWaitSeconds() {
            return 10;
        }

        @Override
        public void detach(Ticket ticket) {
        }
    }
}
