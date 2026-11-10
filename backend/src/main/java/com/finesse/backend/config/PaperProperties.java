package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 논문 실험(P0·P1 비교) 배치 추출용 엔드포인트 설정 — /api/paper/** (논문용 엔드포인트 추가 요청, 11/9).
 * 서비스 엔드포인트·캐시·기존 LLM 설정과는 완전히 따로 둔다. 기본값은 꺼져 있고 실험 기간에만 켠다.
 *
 * @param enabled                true일 때만 PaperController가 등록된다(false면 /api/paper/** 자체가 없음 → 404)
 * @param token                  X-Paper-Token 헤더와 비교할 값 — 환경변수 FINESSE_PAPER_TOKEN으로 받는다. 켰는데 비어 있으면 기동 실패
 * @param llmPath                llm-server 논문용 엔드포인트 경로
 * @param callTimeoutSeconds     생성 호출 1회 제한 — 서비스용 10·15초와 별개, 생성이 중간에 끊기지 않게 넉넉히
 * @param maxUsernamesPerRequest /api/paper/inputs 한 번에 받는 유저 수 상한 — 유저 1명 수집에 약 10초라 앞단 프록시 타임아웃을 넘지 않게
 * @param calcCommit             calc_commit에 넣을 git 해시 — 비어 있으면 기동 시 git rev-parse HEAD로 읽고, 그것도 안 되면 "unknown"
 */
@ConfigurationProperties(prefix = "app.paper")
public record PaperProperties(
        Boolean enabled,
        String token,
        String llmPath,
        Integer callTimeoutSeconds,
        Integer maxUsernamesPerRequest,
        String calcCommit
) {
    public PaperProperties {
        enabled = enabled != null && enabled;
        token = token == null ? "" : token.trim();
        llmPath = llmPath == null || llmPath.isBlank() ? "/v1/paper/generate" : llmPath;
        callTimeoutSeconds = callTimeoutSeconds == null ? 120 : callTimeoutSeconds;
        maxUsernamesPerRequest = maxUsernamesPerRequest == null ? 5 : maxUsernamesPerRequest;
        calcCommit = calcCommit == null ? "" : calcCommit.trim();
    }
}
