package com.finesse.backend.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 사용자명 정규화·검사 — stats·comment 공통.
 * TETR.IO 유저명 규칙(3~16자, 영문·숫자·_·-)에 맞지 않으면 TETR.IO를 부르지 않고 400으로 끝낸다
 * (존재할 수 없는 이름으로 호출 슬롯·503 BUSY 자리를 쓰지 않게). 프론트 lib/username.ts와 같은 규칙.
 */
final class Usernames {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_-]{3,16}$");

    private Usernames() {
    }

    /**
     * 앞뒤 공백을 자르고 소문자로 바꾼다(TETR.IO는 소문자 경로만 받음 — 대문자면 404).
     * Locale.ROOT — 시스템 언어(예: 터키어)에 따라 I가 ı로 바뀌는 일을 막는다.
     */
    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("유저명은 3~16자의 영문·숫자·_·-만 쓸 수 있습니다: " + raw);
        }
        return value;
    }
}
