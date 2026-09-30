package com.finesse.backend.controller;

import com.finesse.backend.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/comment/{username}?scope=light|heavy — Finesse-API명세서 4.2절/4.2-1절.
 * 실제 LLM 라운드로빈 호출 + 재시도/타임아웃 로직은 CommentService/LlmClient에 있음.
 */
@RestController
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    // heavy는 SseEmitter(스트리밍), light는 ResponseEntity(단일 JSON) — 리턴 타입이 달라 Object로 받는다.
    // Spring MVC는 선언 타입이 아니라 실제 반환값 타입으로 처리 방식을 고른다 (12절 4번, 2026-09-29 확정).
    @GetMapping("/api/v1/comment/{username}")
    public Object getComment(@PathVariable String username, @RequestParam String scope) {
        return switch (scope) {
            case "light" -> ResponseEntity.ok(commentService.getLight(username));
            case "heavy" -> commentService.getHeavyStream(username);
            default -> throw new IllegalArgumentException("scope는 light 또는 heavy만 허용됩니다: " + scope);
        };
    }
}
