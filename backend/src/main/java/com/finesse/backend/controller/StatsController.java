package com.finesse.backend.controller;

import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.queue.ClientIp;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/stats/{username} — Finesse-API명세서 4.1절.
 * 실제 TETR.IO 연동 + 계산 로직은 StatsService에 있음.
 */
@Tag(name = "stats", description = "TETR.IO 전적 통계 (API 명세서 4.1)")
@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(summary = "유저 통계 조회",
            description = "최근 300판·1년 창의 전적을 수집해 fixed/delta 지표를 계산한다. 결과는 10분 캐시. "
                    + "누적 또는 수집 경기가 10판 미만이면 cold_start=true, delta_metrics 없음.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "202", description = "대기열 켬(app.queue.enabled)일 때만 — 캐시 미스이고 예상 대기가 5초를 넘음. "
            + "대기표를 받고 GET /api/v1/queue/{ticket_id}를 poll_after_seconds마다 묻는다. done이면 이 주소를 다시 부르면 캐시에서 즉시 온다",
            content = @Content(schema = @Schema(implementation = QueueTicketResponse.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — 없는 유저",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "TETRIO_API_UNAVAILABLE — TETR.IO 호출 실패/20초 초과",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "SERVER_BUSY — 처음 조회하는 유저 수집이 동시 상한(기본 2건)을 넘음. "
            + "Retry-After 헤더(초) 뒤 다시 시도. 대기열 켬이면 줄의 예상 대기가 180초를 넘을 때(Retry-After = 예상 대기, 최대 60초)",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "TOO_MANY_TICKETS — 대기열 켬일 때 한 IP의 대기표가 상한(app.queue.max-tickets-per-ip)을 넘음",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @GetMapping("/api/v1/stats/{username}")
    public StatsResponse getStats(
            @Parameter(description = "TETR.IO 유저명 (대소문자 무관, 소문자로 정규화)", example = "example_user")
            @PathVariable String username,
            @Parameter(description = "true면 캐시를 무시하고 다시 계산 (comment 캐시도 함께 무효화)")
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest request) {
        return statsService.getStats(username, refresh, ClientIp.of(request));
    }
}
