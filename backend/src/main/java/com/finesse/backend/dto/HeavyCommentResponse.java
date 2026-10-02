package com.finesse.backend.dto;

import java.util.List;

/**
 * GET /api/v1/comment/{username}?scope=heavy 응답.
 * Finesse-API명세서 4.2-1절 — 8개 챕터 결과, 일부 실패해도 배열에서 빠지지 않고 status로 표시.
 */
public record HeavyCommentResponse(List<ChapterResult> chapters) {

    // status는 "ok" | "failed" | "timeout" 중 하나 (4.2-1절) — 소문자 고정이라 enum 대신 String 상수 사용
    public static final String STATUS_OK = "ok";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_TIMEOUT = "timeout";

    public record ChapterResult(String chapterId, String status, String footnote, Integer attemptCount) {
    }
}
