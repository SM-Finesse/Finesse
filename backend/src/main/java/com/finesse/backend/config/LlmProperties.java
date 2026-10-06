package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 타임아웃 기준 문서(23번, v0.3) 9절 설정 키 기준 — "호출 1회"와 "엔드포인트"는 다른 레이어라
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
        // TODO(LLM/AI 역할과 협의 필요, Finesse-API명세서 6장): 실제 추론 서버 경로가 아직 정해지지 않아
        // 임시로 가정한 값. 실제 스펙 확정되면 이 두 줄만 바꾸면 됨.
        String lightPath,
        String heavyChapterPath
) {
}
