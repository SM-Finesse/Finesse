package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 타임아웃 기준 문서(23번) 9절 설정 키 기준 — "호출 1회"와 "엔드포인트"는 다른 레이어라
 * 섞어 쓰지 않는다. 엔드포인트 전체 상한은 {@link EndpointProperties}에 별도로 둔다.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        List<String> servers,
        // 연결 타임아웃 — 이 시간 안에 연결 자체가 안 되면 "연결 실패"로 보고 서버 제외 대상이 된다 (5.5절)
        int connectTimeoutSeconds,
        // heavy 챕터 1회 호출 타임아웃 (호출 1회) — 10s
        int heavyCallTimeoutSeconds,
        // heavy 새 시도 시작 조건 — 엔드포인트 잔여 시간이 이 값 미만이면 재시도 시작 안 함
        int heavyMinRemainingSeconds,
        // light 1회 호출 타임아웃 (호출 1회) — 15s. light는 챕터 분할 없이 단일 호출이라 heavy와 분리 관리.
        int lightCallTimeoutSeconds,
        // light 새 시도 시작 조건
        int lightMinRemainingSeconds,
        int maxRetries,
        // 연결 실패·연결 타임아웃 시 그 서버를 이 시간 동안 라운드로빈 대상에서 제외 (타임아웃 기준 문서 23번 5.5절)
        int serverExcludeSeconds,
        // light 경로는 LLM 추론 서버(llm 브랜치 llm-server, PR #9)와 맞춘 값 — 10/7 실제 서버로 연동 확인.
        // heavy 경로는 LLM 쪽 heavy 엔드포인트가 나오면 같은 값인지 확인한다.
        String lightPath,
        String heavyChapterPath
) {
}
