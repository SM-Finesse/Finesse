package com.finesse.backend.trainingdata;

/** users.csv status 열 (작업지시서 4.5절) */
public enum CollectStatus {
    OK("ok"),
    /** 표본 부족 3단계 — 실패가 아니라 정상 제외 */
    INSUFFICIENT("insufficient"),
    NOT_FOUND("not_found"),
    /** 429 — calc가 CollectionStatus.RATE_LIMITED로 알려준다(collect 브랜치) */
    RATE_LIMITED("rate_limited"),
    ERROR("error");

    private final String csv;

    CollectStatus(String csv) {
        this.csv = csv;
    }

    public String csv() {
        return csv;
    }

    /** users.csv의 status 값 → enum. 모르는 값이면 null */
    public static CollectStatus fromCsv(String value) {
        for (CollectStatus s : values()) {
            if (s.csv.equals(value)) {
                return s;
            }
        }
        return null;
    }

    /** 연속 실패(4.6절)에 세고, 명단 끝에서 한 번 더 시도하는 상태 — 표본 부족·없는 유저는 다시 해도 같으니 세지 않는다 */
    public boolean isFailure() {
        return this == RATE_LIMITED || this == ERROR;
    }
}
