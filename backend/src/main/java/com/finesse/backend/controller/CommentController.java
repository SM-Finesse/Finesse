package com.finesse.backend.controller;

import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.HeavyCommentResponse;
import com.finesse.backend.dto.LightCommentResponse;
import com.finesse.backend.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/comment/{username}?scope=light|heavy — Finesse-API명세서 4.2절/4.2-1절.
 * 실제 LLM 라운드로빈 호출 + 재시도/타임아웃 로직은 CommentService/LlmClient에 있음.
 */
@Tag(name = "comment", description = "AI 코멘트 (API 명세서 4.2)")
@RestController
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    // heavy는 SseEmitter(스트리밍), light는 ResponseEntity(단일 JSON) — 리턴 타입이 달라 Object로 받는다.
    // Spring MVC는 선언 타입이 아니라 실제 반환값 타입으로 처리 방식을 고른다 (12절 4번, 2026-09-29 확정).
    @Operation(summary = "AI 코멘트 조회",
            description = """
                    scope=light — 단일 JSON 응답 (요약 + 하이라이트 3개). 엔드포인트 상한 40초.

                    scope=heavy — SSE 스트림(text/event-stream). 챕터가 끝나는 대로 하나씩 보낸다.
                    - `event: chapter` / `id: {chapter_id}` / `data: {챕터 결과 JSON}` — 8건 (chapter_id 중복 없음)
                    - `event: done` / `data: {"completed": ok 챕터 수, "failed_chapters": [...], "meta": {"elapsed_ms": ...}}`
                      — 마지막 1건. 받으면 연결을 닫을 것 (닫지 않으면 브라우저가 자동 재연결함)
                    - `: ping` — 10초마다 보내는 주석 하트비트 (이벤트 아님)
                    - `event: error` / `data: {"error_code": ..., "message": ..., "retry_after_seconds": 5}` — 챕터 시작 전 stats 단계 실패 (retry_after_seconds는 SERVER_BUSY일 때만)
                      (USER_NOT_FOUND · SERVER_BUSY · TETRIO_API_UNAVAILABLE). 이 이벤트 뒤 스트림 종료
                    상한 60초(LLM 서버 1대면 120초) 안에 못 끝난 챕터는 status=timeout으로 채워서 보낸다.
                    성공(ok) 챕터만 챕터 단위로 10분 캐시 — 다시 요청하면 캐시된 챕터는 즉시, 나머지만 LLM 재호출.

                    Swagger의 Try it out은 스트림이 전부 끝난 뒤 한꺼번에 보여준다.
                    순차 도착을 보려면 브라우저 EventSource나 `curl -N`으로 호출할 것.""")
    @ApiResponse(responseCode = "200", description = "light: JSON / heavy: SSE 스트림", content = {
            @Content(mediaType = "application/json", schema = @Schema(implementation = LightCommentResponse.class)),
            @Content(mediaType = "text/event-stream",
                    schema = @Schema(implementation = HeavyCommentResponse.ChapterResult.class,
                            description = "event: chapter 의 data 한 건"))
    })
    @ApiResponse(responseCode = "400", description = "BAD_REQUEST — scope가 light/heavy가 아님",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "LLM_FORMAT_ERROR / TETRIO_API_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "LLM_UNAVAILABLE — light 최종 실패",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @GetMapping("/api/v1/comment/{username}")
    public Object getComment(
            @Parameter(description = "TETR.IO 유저명", example = "icly") @PathVariable String username,
            @Parameter(description = "light 또는 heavy",
                    schema = @Schema(allowableValues = {"light", "heavy"}))
            @RequestParam String scope) {
        return switch (scope) {
            case "light" -> ResponseEntity.ok(commentService.getLight(username));
            // X-Accel-Buffering: no — 앞단 프록시(nginx 등)가 스트림을 모아서 한꺼번에 내보내지 않게
            case "heavy" -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(commentService.getHeavyStream(username));
            default -> throw new IllegalArgumentException("scope는 light 또는 heavy만 허용됩니다: " + scope);
        };
    }
}
