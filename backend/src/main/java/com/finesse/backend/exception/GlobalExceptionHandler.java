package com.finesse.backend.exception;

import com.finesse.backend.dto.ApiErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공통 에러 응답 포맷 적용 — Finesse-API명세서 3장(포맷), 8장(상황별 HTTP 상태 매핑).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("USER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TetrioApiException.class)
    public ResponseEntity<ApiErrorResponse> handleTetrioApiError(TetrioApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("TETRIO_API_UNAVAILABLE", "일시적으로 조회할 수 없습니다, 잠시 후 다시 시도"));
    }

    @ExceptionHandler(ServerBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleServerBusy(ServerBusyException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(new ApiErrorResponse("SERVER_BUSY", "사용자가 많습니다, 잠시 후 다시 시도"));
    }

    @ExceptionHandler(LlmFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmFormatError(LlmFormatException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("LLM_FORMAT_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmUnavailable(LlmUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("LLM_UNAVAILABLE", "일시적으로 코멘트를 생성할 수 없습니다, 잠시 후 다시 시도"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("BAD_REQUEST", ex.getMessage()));
    }
}
