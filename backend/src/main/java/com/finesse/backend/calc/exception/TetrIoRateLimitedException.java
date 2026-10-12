package com.finesse.backend.calc.exception;

/**
 * TETR.IO 429(요청 제한) — collect 브랜치 추가. 학습 데이터 수집기가 users.csv에 rate_limited로 따로 기록하고
 * 대기를 늘리려고 구분한다(학습 데이터 수집 서버 작업지시서 4.5·4.6절). 동작은 기존과 같다: 재시도하지 않는다.
 */
public class TetrIoRateLimitedException extends TetrIoApiException {

    public TetrIoRateLimitedException(Throwable cause) {
        super("TETR.IO 요청 제한(429)", cause, false);
    }
}
