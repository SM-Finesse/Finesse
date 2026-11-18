package com.finesse.backend.queue;

import com.finesse.backend.config.QueueProperties;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.exception.TicketNotFoundException;
import com.finesse.backend.exception.TooManyTicketsException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 발급된 대기표 맵 — 「전적 검색 대기열 문서」 3.5·5.2·5.5절. 서버 메모리에만 둔다(백엔드 1대, 캐시와 같은 방식).
 * <ul>
 *   <li>ticket_id는 추측할 수 없는 임의 값이고 유저명을 넣지 않는다</li>
 *   <li>상태를 묻는 것 자체가 "아직 기다리는 중" 표시 — ticket-idle-seconds(30초) 동안 묻지 않으면 버린다</li>
 *   <li>끝난(done·failed) 대기표는 done-retention-seconds(2분) 뒤 지운다</li>
 *   <li>한 IP가 동시에 가질 수 있는 대기표는 max-tickets-per-ip(0이면 제한 없음). IP는 X-Real-IP 기준({@link ClientIp})</li>
 * </ul>
 * 정리는 5초마다. 서버를 다시 시작하면 맵이 비므로 프론트는 TICKET_NOT_FOUND를 받고 처음부터 다시 검색한다.
 */
@Component
public class TicketRegistry {

    private static final Logger log = LoggerFactory.getLogger(TicketRegistry.class);
    private static final long CLEANUP_SECONDS = 5;

    private final QueueProperties props;
    private final LongSupplier clock;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    // 자리만 잡고 아직 발급 전인 대기표 수(IP별) — reserveIp·publish가 this로 잠그고 쓴다
    private final Map<String, Integer> reservedByIp = new java.util.HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService cleaner;

    @Autowired
    public TicketRegistry(QueueProperties props) {
        this(props, System::nanoTime, props.enabled());
    }

    /** 테스트용 — 시계를 바꿔 끼우고, 정리 스레드 없이 {@link #cleanup()}을 직접 부를 수 있다 */
    public TicketRegistry(QueueProperties props, LongSupplier clock, boolean startCleaner) {
        this.props = props;
        this.clock = clock;
        if (startCleaner) {
            this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "queue-ticket-cleaner");
                t.setDaemon(true);
                return t;
            });
            cleaner.scheduleWithFixedDelay(this::cleanupSafely, CLEANUP_SECONDS, CLEANUP_SECONDS, TimeUnit.SECONDS);
        } else {
            this.cleaner = null;
        }
    }

    @PreDestroy
    void stop() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
    }

    public long now() {
        return clock.getAsLong();
    }

    /** 작업에 붙일 대기표를 만든다 — 아직 발급 전(요청 안 대기). 202로 줄 때 {@link #publish} */
    public Ticket newTicket(QueueJob job, String stage, String ip) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return new Ticket("q_" + HexFormat.of().formatHex(bytes), stage, ip, job, now());
    }

    /** 대기표를 만든다 — reserved면 {@link #reserveIp}로 IP 자리를 잡아 둔 것(발급 때 자리를 대기표로 바꾼다) */
    public Ticket newTicket(QueueJob job, String stage, String ip, boolean reserved) {
        Ticket t = newTicket(job, stage, ip);
        t.reserved = reserved;
        return t;
    }

    /**
     * 이 IP가 대기표를 하나 더 받을 수 있으면 자리를 잡는다 — 넘으면 429 TOO_MANY_TICKETS(5.3·5.5절).
     * 확인과 자리 잡기를 한 번에 해서 같은 IP 요청이 동시에 와도 상한을 넘지 않는다. 잡은 자리는 {@link #publish}가 대기표로 바꾼다.
     *
     * @return 자리를 잡았으면 true, 제한이 없으면(max-tickets-per-ip 0 · IP 모름) false
     */
    public synchronized boolean reserveIp(String ip) {
        int max = props.maxTicketsPerIp();
        if (max <= 0 || ip == null) {
            return false;
        }
        long active = tickets.values().stream().filter(t -> !t.isFinished() && ip.equals(t.ip)).count()
                + reservedByIp.getOrDefault(ip, 0);
        if (active >= max) {
            log.info("IP 대기표 상한 {}개 — 429 TOO_MANY_TICKETS", max);
            throw new TooManyTicketsException();
        }
        reservedByIp.merge(ip, 1, Integer::sum);
        return true;
    }

    /** 대기표 발급 — 202 본문(event: queued data)을 돌려준다 */
    public QueueTicketResponse publish(Ticket ticket) {
        ticket.issued = true;
        ticket.lastSeenNanos = now();
        synchronized (this) {
            tickets.put(ticket.id, ticket);
            if (ticket.reserved) {
                ticket.reserved = false;
                reservedByIp.computeIfPresent(ticket.ip, (k, v) -> v <= 1 ? null : v - 1);
            }
        }
        Integer est = ticket.isFinished() ? null : ticket.job.estimatedWaitSeconds();
        Integer position = ticket.isFinished() || ticket.job.isRunning() ? null : ticket.job.position();
        return QueueTicketResponse.issued(ticket.id, ticket.stage, position, est, pollAfter(est));
    }

    /** GET /api/v1/queue/{ticket_id} — 묻는 것 자체가 "아직 기다리는 중" 표시 */
    public QueueTicketResponse view(String ticketId) {
        Ticket t = ticketId == null ? null : tickets.get(ticketId);
        if (t == null) {
            throw new TicketNotFoundException();
        }
        t.lastSeenNanos = now();
        if (t.isFinished()) {
            return QueueTicketResponse.state(t.succeeded() ? QueueTicketResponse.STATUS_DONE : QueueTicketResponse.STATUS_FAILED,
                    t.stage, null, null, props.pollAfterSeconds(), t.errorCode());
        }
        boolean running = t.job.isRunning();
        int est = t.job.estimatedWaitSeconds();
        return QueueTicketResponse.state(running ? QueueTicketResponse.STATUS_RUNNING : QueueTicketResponse.STATUS_WAITING,
                t.stage, running ? null : t.job.position(), est, pollAfter(est), null);
    }

    /** DELETE /api/v1/queue/{ticket_id} — 내 대기표만 지운다. 작업은 바라보는 대기표가 없고 시작 전일 때만 줄에서 빠진다 */
    public void cancel(String ticketId) {
        Ticket t = ticketId == null ? null : tickets.remove(ticketId);
        if (t == null) {
            throw new TicketNotFoundException();
        }
        if (!t.isFinished()) {
            log.info("대기표 취소 {} ({})", t.id, t.stage);
            t.job.detach(t);
        }
    }

    /** poll_after_seconds — 보통 기본값(2초), 줄이 길면 최대 max-poll-after-seconds(5초) */
    public int pollAfter(Integer estimatedWaitSeconds) {
        int base = props.pollAfterSeconds();
        if (estimatedWaitSeconds == null) {
            return base;
        }
        int scaled = (int) Math.ceil(estimatedWaitSeconds / 20.0);
        return Math.max(base, Math.min(props.maxPollAfterSeconds(), scaled));
    }

    /** 만료 정리 — 30초 동안 묻지 않은 대기표는 버리고, 끝난 대기표는 2분 뒤 지운다 */
    public void cleanup() {
        long now = now();
        long idle = TimeUnit.SECONDS.toNanos(props.ticketIdleSeconds());
        long retention = TimeUnit.SECONDS.toNanos(props.doneRetentionSeconds());
        for (Ticket t : tickets.values()) {
            if (t.isFinished()) {
                if (now - t.finishedNanos > retention) {
                    tickets.remove(t.id, t);
                }
            } else if (now - t.lastSeenNanos > idle) {
                if (tickets.remove(t.id, t)) {
                    log.info("대기표 만료(30초 조회 없음) {} ({})", t.id, t.stage);
                    t.job.detach(t);
                }
            }
        }
    }

    private void cleanupSafely() {
        try {
            cleanup();
        } catch (RuntimeException e) {
            log.warn("대기표 정리 실패", e);
        }
    }

    /** 테스트·확인용 */
    public int size() {
        return tickets.size();
    }
}
