package com.finesse.backend.dto;

/**
 * 공통 에러 응답 포맷 (Finesse-API명세서 3장 확정): { "error_code": "...", "message": "..." }
 */
public record ApiErrorResponse(String errorCode, String message) {
}
