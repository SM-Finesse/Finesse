package com.finesse.backend.dto;

import java.util.List;

/**
 * GET /api/v1/comment/{username}?scope=light 응답.
 * 기능 명세서 3.5절 확정 스키마.
 */
public record LightCommentResponse(String lightSummary, List<Highlight> highlights) {

    public record Highlight(String stat, String sentence) {
    }
}
