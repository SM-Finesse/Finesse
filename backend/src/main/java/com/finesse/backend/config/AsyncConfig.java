package com.finesse.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * heavy 8챕터 병렬 호출용 스레드풀 (Finesse-API명세서 6.1절 — 스레드풀 크기는 서버 코어 수 기준).
 * 실제 동시 처리 1건 제약은 서버별 전담 워커(LlmClient의 서버별 큐)가 맡으므로, 이 풀은 그 큐에
 * 제출/대기하는 챕터 개수만큼만 있으면 된다(대기 자체는 큐가 흡수).
 * 빈 이름(메서드 이름)이 곧 주입 시 @Qualifier 값이므로 이름을 그대로 유지할 것.
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ExecutorService llmExecutor() {
        return Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
    }

    // stats 엔드포인트 상한(EndpointProperties.statsSeconds) 강제용 — TetrioClient 호출은 블로킹이라
    // 별도 스레드에서 돌리고 Future.get(timeout)으로 마감을 건다.
    @Bean
    public ExecutorService statsExecutor() {
        return Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
    }
}
