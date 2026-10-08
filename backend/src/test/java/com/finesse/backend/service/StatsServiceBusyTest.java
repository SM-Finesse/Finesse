package com.finesse.backend.service;

import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.EndpointProperties;
import com.finesse.backend.config.StatsLoadProperties;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 트래픽 몰림(타임아웃 기준 문서 23번 5.4절) — 캐시 미스 수집 동시 상한. 1단계: 대기열 없이 바로 503 BUSY,
 * 2단계: 정해진 시간까지 차례를 기다리고, 대기열이 차 있거나 시간 안에 자리가 안 나면 503 BUSY.
 * CacheManager는 캐시를 돌려주지 않게 두어(getCache → null) 매 요청이 수집 경로를 타게 한다.
 */
class StatsServiceBusyTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService callers = Executors.newFixedThreadPool(3);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
        callers.shutdownNow();
    }

    private StatsService service(StatCalculatorFacade facade, int maxConcurrent, int statsSeconds) {
        return service(facade, new StatsLoadProperties(maxConcurrent, 5), statsSeconds);
    }

    private StatsService service(StatCalculatorFacade facade, StatsLoadProperties load, int statsSeconds) {
        return new StatsService(facade, mock(TetrioClient.class), mock(CacheManager.class),
                new EndpointProperties(statsSeconds, 40, 60, 120), load, executor);
    }

    /** getStats를 다른 스레드에서 부르고, 끝나면 던진 예외(정상이면 null)를 돌려준다 */
    private CompletableFuture<Throwable> callAsync(StatsService service, String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                service.getStats(username, false);
                return null;
            } catch (Throwable t) {
                return t;
            }
        }, callers);
    }

    /**
     * analyze(username)가 release될 때까지 멈춰 있는 facade — entered는 수집이 시작됐다는 신호.
     * 마감 때 cancel(true)의 interrupt도 무시한다: 블로킹 HTTP 호출처럼 끊기지 않고 계속 도는 수집을 흉내 낸다.
     */
    private static StatCalculatorFacade blockingFacade(String username, CountDownLatch entered, CountDownLatch release) {
        StatCalculatorFacade facade = mock(StatCalculatorFacade.class);
        when(facade.analyze(username)).thenAnswer(inv -> {
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (release.getCount() > 0 && System.nanoTime() < until) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 계속 진행 — 중단 신호를 받아도 수집은 끝까지 간다
                }
            }
            return new AnalysisOutcome.UserNotFound(username);
        });
        when(facade.analyze("free")).thenReturn(new AnalysisOutcome.UserNotFound("free"));
        return facade;
    }

    @Test
    void 동시_수집_상한을_넘은_요청은_바로_503_BUSY() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatsService service = service(blockingFacade("slow", entered, release), 1, 20);

        CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> {
            try {
                service.getStats("slow", false);
                return null;
            } catch (Throwable t) {
                return t;
            }
        }, callers);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.getStats("free", false))
                .isInstanceOfSatisfying(ServerBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(5));
        // 대기열을 끈 설정(1단계)이면 기다리지 않고 바로 거절해야 한다
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(UserNotFoundException.class);
        // 자리가 반납됐으니 다음 요청은 BUSY가 아니라 정상 처리(여기선 없는 유저 404)
        assertThatThrownBy(() -> service.getStats("free", false)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void 마감으로_502가_나가도_수집이_끝날_때까지_자리를_반납하지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatsService service = service(blockingFacade("slow", entered, release), 1, 1);

        // stats 마감 1초 — 수집은 아직 안 끝났지만 요청은 502(TetrioApiException)로 먼저 끝난다
        assertThatThrownBy(() -> service.getStats("slow", false)).isInstanceOf(TetrioApiException.class);
        assertThat(entered.getCount()).isZero();

        // calc 안의 수집이 아직 돌고 있으므로 새 수집은 여전히 BUSY
        assertThatThrownBy(() -> service.getStats("free", false)).isInstanceOf(ServerBusyException.class);

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Throwable last;
        do {
            Thread.sleep(50);
            try {
                service.getStats("free", false);
                last = null;
            } catch (Throwable t) {
                last = t;
            }
        } while (last instanceof ServerBusyException && System.nanoTime() < deadline);
        assertThat(last).isInstanceOf(UserNotFoundException.class);
    }

    // ---- 2단계 대기열 (23번 5.4절): 자리가 없으면 정해진 시간까지 차례를 기다린다

    @Test
    void 자리가_없으면_기다렸다가_자리가_나면_처리한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatsService service = service(blockingFacade("slow", entered, release),
                new StatsLoadProperties(1, 5, 3, 1), 20);

        CompletableFuture<Throwable> first = callAsync(service, "slow");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        CompletableFuture<Throwable> waiting = callAsync(service, "free");
        Thread.sleep(500);
        assertThat(waiting).isNotDone(); // 503을 바로 내지 않고 기다리는 중
        release.countDown();

        // 앞 수집이 끝나 자리가 나면 이어서 처리 — BUSY가 아니라 정상 처리(여기선 없는 유저 404)
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(UserNotFoundException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(400);
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void 대기열이_차_있으면_기다리지_않고_바로_503() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatsService service = service(blockingFacade("slow", entered, release),
                new StatsLoadProperties(1, 5, 3, 1), 20);

        CompletableFuture<Throwable> first = callAsync(service, "slow");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> waiting = callAsync(service, "free"); // 대기 1명 — 자리 꽉 참
        Thread.sleep(300);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.getStats("free", false)).isInstanceOf(ServerBusyException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);

        release.countDown();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(UserNotFoundException.class);
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void 정해진_시간까지_기다려도_자리가_안_나면_503() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatsService service = service(blockingFacade("slow", entered, release),
                new StatsLoadProperties(1, 5, 1, 1), 20);

        CompletableFuture<Throwable> first = callAsync(service, "slow");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.getStats("free", false))
                .isInstanceOfSatisfying(ServerBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(5));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(waitedMs).isBetween(900L, 3000L); // 대기 1초를 채우고 거절

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }
}
