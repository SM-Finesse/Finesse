package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 라이트 개별·기본 코멘트(LLM light-v5.2, 10/21) 호출 설정 — 판정·선정은 calc, LLM은 고른 지표를 설명만 한다.
 * 값을 적지 않으면 아래 기본값으로 동작한다.
 *
 * @param enabled             true면 calc가 고른 지표로 개별 3회 + 기본 1회를 부른다. false면 예전 한 번 호출(/v1/comment/light)
 * @param sentencePath        개별 코멘트 경로
 * @param summaryPath         기본 코멘트 경로
 * @param callTimeoutSeconds  호출 1회 제한 — LLM 서버가 받은 시각부터 9초 안에 응답(강제 마감)하므로 10초
 * @param minRemainingSeconds 다른 서버로 다시 부르려면 light 엔드포인트에 남아 있어야 하는 시간 — 부족하면 부록 A 문장 틀을 쓴다
 */
@ConfigurationProperties(prefix = "llm.light-comment")
public record LightCommentProperties(
        Boolean enabled,
        String sentencePath,
        String summaryPath,
        Integer callTimeoutSeconds,
        Integer minRemainingSeconds
) {
    public LightCommentProperties {
        enabled = enabled == null || enabled;
        sentencePath = sentencePath == null ? "/v1/light/sentence" : sentencePath;
        summaryPath = summaryPath == null ? "/v1/light/summary" : summaryPath;
        callTimeoutSeconds = callTimeoutSeconds == null ? 10 : callTimeoutSeconds;
        minRemainingSeconds = minRemainingSeconds == null ? 10 : minRemainingSeconds;
    }

    public static LightCommentProperties defaults() {
        return new LightCommentProperties(null, null, null, null, null);
    }
}
