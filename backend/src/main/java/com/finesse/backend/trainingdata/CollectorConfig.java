package com.finesse.backend.trainingdata;

import com.finesse.backend.calc.collector.RateLimiter;
import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.TetrioProperties;
import com.finesse.backend.service.TrainingPayloadBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;

/**
 * collector 프로파일에서만 켜지는 빈 — 서비스(기본 프로파일)에는 아무것도 추가되지 않는다.
 * 실행: java -jar ... --spring.profiles.active=collector (backend/collector/README.md)
 */
@Configuration
@Profile("collector")
public class CollectorConfig {

    private static final Logger log = LoggerFactory.getLogger(CollectorConfig.class);

    /**
     * 프로필 패널용 /users/{username} 호출을 막는다 — payload에 들어가지 않는 값이고, 유저당 TETR.IO 호출을
     * 요약 1 + 기록 3 = 4회로 맞춘다(작업지시서 4.2절). StatsService가 이 빈을 받는다.
     */
    @Bean
    @Primary
    TetrioClient collectorTetrioClient(@Qualifier("tetrioWebClient") WebClient tetrioWebClient,
                                       TetrioProperties props, RateLimiter rateLimiter) {
        return new TetrioClient(tetrioWebClient, props, rateLimiter) {
            @Override
            public UserInfo fetchUserInfo(String usernameLower, String sessionId) {
                return UserInfo.empty();
            }
        };
    }

    @Bean
    TrainingDataCollector trainingDataCollector(TrainingPayloadBuilder builder, JsonMapper jsonMapper,
                                                TrainingCollectorProperties props) {
        return new TrainingDataCollector(builder, jsonMapper, props, Clock.systemDefaultZone(), d -> Thread.sleep(d),
                Path.of("").toAbsolutePath());
    }

    /** 명단을 끝까지 돌거나 중단되면 종료 코드를 남기고 프로세스를 끝낸다(0 완료 · 1 설정 오류 · 2 중단 · 3 이미 실행 중) */
    @Bean
    ApplicationRunner collectorRunner(TrainingDataCollector collector, ApplicationContext context) {
        return args -> {
            int code;
            try {
                code = collector.run();
            } catch (Exception e) {
                log.error("수집기가 예외로 멈췄습니다: {}", e.getClass().getName());
                code = TrainingDataCollector.EXIT_CONFIG;
            }
            int exit = code;
            System.exit(SpringApplication.exit(context, () -> exit));
        };
    }
}
