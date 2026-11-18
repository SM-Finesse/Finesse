package com.finesse.backend.queue;

import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.exception.TooManyTicketsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 입장 판정과 코멘트 대기표 — 「전적 검색 대기열 문서」 4.2·4.3절, Q8·Q13·Q14·Q18 쪽 판정 규칙.
 * LLM 부하는 가짜(값을 직접 정함)로 대신한다.
 */
class CommentQueueTest {

    /** 정해 둔 값을 돌려주는 LLM 부하 */
    static final class FakeLoad implements CommentQueue.LlmLoad {
        volatile int servers = 2;
        volatile double pending;

        @Override
        public int assignableServers() {
            return servers;
        }

        @Override
        public double pendingWorkSeconds() {
            return pending;
        }

        @Override
        public double jobWorkSeconds(CommentQueue.Kind kind) {
            return kind == CommentQueue.Kind.LIGHT ? 6 : 16;
        }

        @Override
        public double jobDurationSeconds(CommentQueue.Kind kind) {
            return kind == CommentQueue.Kind.LIGHT ? 5 : 8;
        }
    }

    private final FakeLoad load = new FakeLoad();
    private CommentQueue queue;
    private TicketRegistry registry;

    @AfterEach
    void shutdown() {
        if (queue != null) {
            queue.stop();
        }
    }

    private CommentQueue queue(int maxTicketsPerIp) {
        QueueProperties p = new QueueProperties(true, 5, 180, 30, 2, true, 15, 30, maxTicketsPerIp, 5, 120, 5, 0.3, 60, 60, 0, 1.5, 3.5);
        registry = new TicketRegistry(p, System::nanoTime, false);
        queue = new CommentQueue(p, registry, load, System::nanoTime, false); // 자동 판정 끔 — dispatch()를 직접 부른다
        return queue;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("조건을 기다리다 시간 초과");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void LLM_예상_대기가_기준_이하면_요청_안에서_만든다() {
        queue(0);
        load.pending = 28; // 서버 2대 → 14초 ≤ light 15초
        assertThat(queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> { }, "ip"))
                .isInstanceOf(CommentQueue.Admission.Inline.class);
        load.pending = 58; // 29초 ≤ heavy 30초
        assertThat(queue.admit(CommentQueue.Kind.HEAVY, "b", false, () -> { }, "ip"))
                .isInstanceOf(CommentQueue.Admission.Inline.class);
    }

    @Test
    void Q8_light_기준을_넘으면_대기표_stage_comment_LLM이_풀리면_차례대로_만들고_done() throws Exception {
        queue(0);
        load.pending = 40; // 20초 > 15초
        List<String> ran = new CopyOnWriteArrayList<>();
        CommentQueue.Admission a = queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> ran.add("a"), "ip");

        assertThat(a).isInstanceOf(CommentQueue.Admission.Queued.class);
        Ticket ticket = ((CommentQueue.Admission.Queued) a).ticket();
        QueueTicketResponse body = registry.publish(ticket);
        assertThat(body.stage()).isEqualTo(QueueTicketResponse.STAGE_COMMENT);
        assertThat(body.position()).isEqualTo(1);
        assertThat(body.estimatedWaitSeconds()).isEqualTo(20);

        queue.dispatch();
        assertThat(ran).as("아직 밀려 있으면 시작하지 않는다").isEmpty();
        load.pending = 10; // 5초
        queue.dispatch();
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(ticket.id()).status()));
        assertThat(ran).containsExactly("a");
    }

    @Test
    void Q14_보류_작업이_있으면_라이트_헤비_구분_없이_들어온_순서대로() throws Exception {
        queue(0);
        List<String> ran = new CopyOnWriteArrayList<>();
        load.pending = 40; // 20초 — light 기준(15) 초과, heavy 기준(30) 이하
        CommentQueue.Admission light = queue.admit(CommentQueue.Kind.LIGHT, "l", true, () -> ran.add("light"), "ip");
        CommentQueue.Admission heavy = queue.admit(CommentQueue.Kind.HEAVY, "h", false, () -> ran.add("heavy"), "ip");

        assertThat(light).isInstanceOf(CommentQueue.Admission.Queued.class);
        assertThat(heavy).as("heavy 기준 아래여도 먼저 온 light 뒤에 선다").isInstanceOf(CommentQueue.Admission.Queued.class);
        Ticket heavyTicket = ((CommentQueue.Admission.Queued) heavy).ticket();
        assertThat(registry.publish(heavyTicket).position()).isEqualTo(2);
        assertThat(heavyTicket.stage()).isEqualTo(QueueTicketResponse.STAGE_HEAVY);

        load.pending = 0;
        queue.dispatch();
        await(() -> ran.size() == 1);
        queue.dispatch();
        await(() -> ran.size() == 2);
        assertThat(ran).containsExactly("light", "heavy");
    }

    @Test
    void Q18_배정할_수_있는_서버가_줄면_예상_대기가_늘어나고_0대면_입장_판정_없이_지금처럼() {
        queue(0);
        load.pending = 20;
        load.servers = 2; // 10초 → inline
        assertThat(queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> { }, "ip"))
                .isInstanceOf(CommentQueue.Admission.Inline.class);
        load.servers = 1; // 20초 → 대기표
        assertThat(queue.admit(CommentQueue.Kind.LIGHT, "b", true, () -> { }, "ip"))
                .isInstanceOf(CommentQueue.Admission.Queued.class);

        queue(0);
        load.servers = 0; // 두 서버 모두 연결 실패로 제외 — 지금처럼(문장 틀)
        load.pending = 1000;
        assertThat(queue.admit(CommentQueue.Kind.LIGHT, "c", true, () -> { }, "ip"))
                .isInstanceOf(CommentQueue.Admission.Inline.class);
    }

    @Test
    void 같은_유저_light가_생성_중이면_합류해서_기다리고_heavy는_대기표를_붙인다() throws Exception {
        queue(0);
        CountDownLatch hold = new CountDownLatch(1);
        load.pending = 0;
        queue.prefetch(CommentQueue.Kind.LIGHT, "a", () -> {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CommentQueue.Admission join = queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> { }, "ip");
        assertThat(join).isInstanceOf(CommentQueue.Admission.Join.class);
        hold.countDown();
        ((CommentQueue.Admission.Join) join).job().done().get(5, TimeUnit.SECONDS);

        CountDownLatch heavyHold = new CountDownLatch(1);
        load.pending = 100; // 50초 — 대기표 작업
        CommentQueue.Admission first = queue.admit(CommentQueue.Kind.HEAVY, "h", false, () -> {
            try {
                heavyHold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ip");
        load.pending = 0;
        queue.dispatch(); // 시작(생성 중)
        CommentQueue.Admission second = queue.admit(CommentQueue.Kind.HEAVY, "h", false, () -> { }, "ip2");
        assertThat(second).as("스트림은 다른 요청의 생성에 이어 붙일 수 없어 대기표").isInstanceOf(CommentQueue.Admission.Queued.class);
        Ticket t1 = ((CommentQueue.Admission.Queued) first).ticket();
        Ticket t2 = ((CommentQueue.Admission.Queued) second).ticket();
        registry.publish(t1);
        registry.publish(t2);
        assertThat(registry.view(t2.id()).status()).isEqualTo(QueueTicketResponse.STATUS_RUNNING);
        heavyHold.countDown();
        await(() -> QueueTicketResponse.STATUS_DONE.equals(registry.view(t1.id()).status())
                && QueueTicketResponse.STATUS_DONE.equals(registry.view(t2.id()).status()));
    }

    @Test
    void 대기표를_모두_취소하면_시작_전_작업은_빠지고_미리_만들기_작업은_대기표가_없어도_남는다() {
        queue(0);
        load.pending = 100;
        List<String> ran = new CopyOnWriteArrayList<>();
        CommentQueue.Admission a = queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> ran.add("a"), "ip");
        queue.prefetch(CommentQueue.Kind.LIGHT, "p", () -> ran.add("p"));
        Ticket t = ((CommentQueue.Admission.Queued) a).ticket();
        registry.publish(t);
        assertThat(queue.heldCount()).isEqualTo(2);

        registry.cancel(t.id());
        assertThat(queue.heldCount()).isEqualTo(1);
    }

    @Test
    void 코멘트_대기표도_IP_상한을_센다() {
        queue(1);
        load.pending = 100;
        Ticket t = ((CommentQueue.Admission.Queued) queue.admit(CommentQueue.Kind.LIGHT, "a", true, () -> { }, "9.9.9.9")).ticket();
        registry.publish(t);
        assertThatThrownBy(() -> queue.admit(CommentQueue.Kind.HEAVY, "b", false, () -> { }, "9.9.9.9"))
                .isInstanceOf(TooManyTicketsException.class);
    }
}
