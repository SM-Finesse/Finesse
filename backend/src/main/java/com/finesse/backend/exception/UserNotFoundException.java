package com.finesse.backend.exception;

/**
 * TETR.IO에 존재하지 않는 유저명 조회 시 발생 (FR-01, Finesse-API명세서 8장 → HTTP 404).
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String username) {
        super("유저를 찾을 수 없습니다: " + username);
    }
}
