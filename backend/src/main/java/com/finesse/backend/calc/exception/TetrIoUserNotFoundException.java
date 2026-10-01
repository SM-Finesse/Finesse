package com.finesse.backend.calc.exception;

/** 존재하지 않는 TETR.IO 유저 (summaries/league 호출이 HTTP 404) */
public class TetrIoUserNotFoundException extends RuntimeException {

    private final String username;

    public TetrIoUserNotFoundException(String username) {
        super("TETR.IO 유저를 찾을 수 없음: " + username);
        this.username = username;
    }

    public String username() {
        return username;
    }
}