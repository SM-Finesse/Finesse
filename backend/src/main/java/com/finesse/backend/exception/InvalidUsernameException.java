package com.finesse.backend.exception;

/**
 * 유저명 형식 오류 — 400 INVALID_USERNAME (QA 검수요청서 R-03, 10/12).
 * 잘못된 scope 같은 일반 400(BAD_REQUEST)과 구분하고, 메시지에 입력값을 넣지 않는다(API 명세서 3절: message에 유저명 등 내부 정보 금지).
 */
public class InvalidUsernameException extends IllegalArgumentException {

    public static final String MESSAGE = "유저명은 3~16자의 영문·숫자·_·-만 쓸 수 있습니다";

    public InvalidUsernameException() {
        super(MESSAGE);
    }
}
