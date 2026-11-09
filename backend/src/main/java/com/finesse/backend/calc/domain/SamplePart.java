package com.finesse.backend.calc.domain;

import java.util.Locale;

/**
 * 신뢰도 판정에 쓴 표본의 종류 (「지표 신뢰도 판정 기준」 1.6절).
 * ALL = 지표 값에 들어간 매치, Q1·Q5 = strength_split 분위, ROUND_INDICES = 서로 다른 라운드 순번 수,
 * COMEBACK·COMEBACK_AGAINST = 역전 기회·역전 허용 기회.
 */
public enum SamplePart {
    ALL, Q1, Q5, ROUND_INDICES, COMEBACK, COMEBACK_AGAINST;

    /** 응답에 쓰는 소문자 이름 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}