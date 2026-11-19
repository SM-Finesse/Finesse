package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 전적 검색 대기열 설정 — 「전적 검색 대기열 문서」 6절 설정 표.
 * enabled=false면 지금 동작(요청 안 대기 6초·2명, 넘으면 503, LLM 대기열 라이트 먼저) 그대로다.
 * 202를 모르는 지금 프론트가 202 본문을 결과로 읽으면 깨지므로, 프론트 대기 화면이 들어간 뒤 켠다(5.4절).
 *
 * @param inlineWaitSeconds          예상 대기가 이 값 이하면 요청 안에서 기다리고, 넘으면 대기표(3.3절)
 * @param maxWaitSeconds             예상 대기가 이 값을 넘으면 줄에 넣지 않고 503 SERVER_BUSY(3.4절)
 * @param ticketIdleSeconds          프론트가 이 시간 동안 상태를 묻지 않으면 대기표를 버린다(3.5절)
 * @param pollAfterSeconds           poll_after_seconds 기본값. 줄이 길면 최대 maxPollAfterSeconds(5.2절)
 * @param prefetchLight              수집이 끝나면 라이트 코멘트를 같은 순서로 미리 만든다(4.1절)
 * @param lightInlineWaitSeconds     LLM 예상 대기가 이 값 이하면 light를 요청 안에서 만든다(4.2절)
 * @param heavyInlineWaitSeconds     LLM 예상 대기가 이 값 이하면 heavy를 지금처럼 SSE로 바로 보낸다(4.3절)
 * @param maxTicketsPerIp            한 IP가 동시에 가질 수 있는 대기표 수, 0이면 제한 없음(5.5절)
 * @param maxPollAfterSeconds        poll_after_seconds 상한
 * @param doneRetentionSeconds       끝난(done·failed) 대기표를 지우기까지(3.5절 2분)
 * @param initialIntervalSeconds     수집 완료 간격 이동 평균의 처음값(3.3절 5초)
 * @param intervalAlpha              수집 완료 간격 이동 평균(EWMA)의 α(3.3절 0.3)
 * @param maxRetryAfterSeconds       줄이 찼을 때 503 Retry-After 상한(3.4절 60초)
 * @param refreshMinIntervalSeconds  마지막 갱신 후 이 시간 안의 refresh=true는 줄을 세우지 않고 캐시를 돌려준다(5.5절), 0이면 끔
 * @param testFixedIntervalSeconds   테스트 전용(10절) — 0보다 크면 수집 1건을 최소 이 시간으로 맞추고 예상 대기도 이 값으로 계산한다
 * @param initialLightCallSeconds    LLM light 호출 1회 처리 시간 이동 평균의 처음값(4.2절). 실제 LLM 서버 실측 약 1.5초(11/18)
 * @param initialHeavyCallSeconds    LLM heavy 챕터 호출 1회 처리 시간 이동 평균의 처음값(4.3절). 실제 LLM 서버 실측 약 3.4초(11/18) —
 *                                   켜자마자 heavy가 몰리면 실제 시간을 배우기 전에 입장 판정을 하므로 처음값이 짧으면 너무 많이 받아들인다
 */
@ConfigurationProperties(prefix = "app.queue")
public record QueueProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int inlineWaitSeconds,
        @DefaultValue("180") int maxWaitSeconds,
        @DefaultValue("30") int ticketIdleSeconds,
        @DefaultValue("2") int pollAfterSeconds,
        @DefaultValue("true") boolean prefetchLight,
        @DefaultValue("15") int lightInlineWaitSeconds,
        @DefaultValue("30") int heavyInlineWaitSeconds,
        @DefaultValue("3") int maxTicketsPerIp,
        @DefaultValue("5") int maxPollAfterSeconds,
        @DefaultValue("120") int doneRetentionSeconds,
        @DefaultValue("5") double initialIntervalSeconds,
        @DefaultValue("0.3") double intervalAlpha,
        @DefaultValue("60") int maxRetryAfterSeconds,
        @DefaultValue("60") int refreshMinIntervalSeconds,
        @DefaultValue("0") int testFixedIntervalSeconds,
        @DefaultValue("1.5") double initialLightCallSeconds,
        @DefaultValue("3.5") double initialHeavyCallSeconds
) {

    /** 대기열 꺼짐 — 테스트와 예전 생성자용 */
    public static QueueProperties disabled() {
        return new QueueProperties(false, 5, 180, 30, 2, true, 15, 30, 3, 5, 120, 5, 0.3, 60, 60, 0, 1.5, 3.5);
    }

    /** 대기열 켬, 나머지는 문서 기본값 — 테스트용 */
    public static QueueProperties enabledDefaults() {
        return new QueueProperties(true, 5, 180, 30, 2, true, 15, 30, 3, 5, 120, 5, 0.3, 60, 60, 0, 1.5, 3.5);
    }
}
