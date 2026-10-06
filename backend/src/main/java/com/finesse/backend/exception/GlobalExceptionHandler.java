package com.finesse.backend.exception;

import com.finesse.backend.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공통 에러 응답 포맷 적용 — Finesse-API명세서 3장(포맷), 8장(상황별 HTTP 상태 매핑).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    // 필수 파라미터 누락(예: scope 없음) — 공통 포맷으로. 처리하지 않으면 Spring 기본 오류 응답이 나가고,
    // 개발 모드(devtools)에서는 서버 내부 스택트레이스까지 응답 본문에 실린다.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("BAD_REQUEST", "필수 파라미터가 없습니다: " + ex.getParameterName()));
    }

    // 파라미터 형식 오류(예: refresh=abc) — 400
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(TypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("BAD_REQUEST", "파라미터 형식이 잘못되었습니다: " + ex.getPropertyName()));
    }

    /**
     * 위에서 처리하지 않은 예외 — 내부 정보(스택트레이스) 없이 공통 포맷으로.
     * Spring이 상태 코드를 아는 예외(없는 경로 404·지원 안 하는 메서드 405 등, ErrorResponse)는 그 상태 코드 그대로,
     * 나머지는 500. SSE 비동기 응답이 이미 끊겼거나 시간이 다 된 경우는 본문을 쓸 수 없으니 Spring 기본 처리에 맡긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AsyncRequestNotUsableException || ex instanceof AsyncRequestTimeoutException) {
            throw ex;
        }
        if (ex instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            String code = status != null ? status.name() : "ERROR";
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders())
                    .body(new ApiErrorResponse(code, detail != null ? detail : code));
        }
        log.error("처리되지 않은 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "일시적인 오류가 발생했습니다, 잠시 후 다시 시도"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("BAD_REQUEST", ex.getMessage()));
    }
}
