package com.finesse.backend.trainingdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * collector 프로파일 — 수집한 유저마다 LLM 코멘트를 받아 함께 저장할 때의 설정 (10/27).
 *
 * @param servers             LLM 서버 주소 목록. 비어 있으면 코멘트를 받지 않는다(예전 동작).
 *                            🔴 실제 주소는 레포에 넣지 않는다 — 수집 폴더의 collector-local.yml에 둔다
 * @param callTimeoutSeconds  호출 1회 — LLM 서버가 요청을 받은 시각부터 9초 안에 응답(강제 마감)하므로 10초
 * @param connectTimeoutSeconds 연결 — 넘으면 다른 서버로
 * @param fallbackRetries     LLM 서버가 대체 문장(fallback: true)을 주면 같은 요청을 다시 보내는 횟수
 * @param sentencePath        라이트 개별 코멘트 (LLM 연동 명세 light-v5.6d 2.1절)
 * @param summaryPath         라이트 기본 코멘트 (2.2절)
 * @param heavyPath           헤비 챕터 코멘트
 */
@Profile("collector")
@ConfigurationProperties(prefix = "collector.llm")
public record CollectorLlmProperties(
        @DefaultValue("") List<String> servers,
        @DefaultValue("10") int callTimeoutSeconds,
        @DefaultValue("2") int connectTimeoutSeconds,
        @DefaultValue("1") int fallbackRetries,
        @DefaultValue("/v1/light/sentence") String sentencePath,
        @DefaultValue("/v1/light/summary") String summaryPath,
        @DefaultValue("/v1/comment/heavy-chapter") String heavyPath
) {
    public CollectorLlmProperties {
        servers = servers == null ? List.of() : servers.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    public boolean enabled() {
        return !servers.isEmpty();
    }
}
