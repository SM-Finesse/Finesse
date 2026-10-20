package com.finesse.backend.calc.domain;

import java.util.Locale;

/**
 * 라이트 뷰 하이라이트 1단계 판정 영역 (설계서 30.3절, v4.2).
 * HIGH·LOW = 보통 범위 밖, NORMAL = 범위 안(경계 포함), NONE = 값이 없거나 판정하지 않음.
 */
public enum HighlightZone {
    HIGH, NORMAL, LOW, NONE;

    /** 응답·작업 시트에 쓰는 소문자 이름 ("high" · "normal" · "low" · "none") */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}