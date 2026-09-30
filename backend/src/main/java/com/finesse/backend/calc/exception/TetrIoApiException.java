package com.finesse.backend.calc.exception;

/** TETR.IO API 호출 실패(타임아웃·5xx·429·응답 형식 오류 등) — 페이지 단위 실패로 처리한다 (설계서 3.7절). */
public class TetrIoApiException extends RuntimeException {

    public TetrIoApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public TetrIoApiException(String message) {
        super(message);
    }
}