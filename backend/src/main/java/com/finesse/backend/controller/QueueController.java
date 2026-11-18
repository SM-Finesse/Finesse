package com.finesse.backend.controller;

import com.finesse.backend.dto.ApiErrorResponse;
import com.finesse.backend.dto.QueueTicketResponse;
import com.finesse.backend.queue.TicketRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기표 상태 — 「전적 검색 대기열 문서」 5.2절. 대기표는 stats·comment(light)의 202 본문, heavy의 event: queued로 받는다.
 */
@Tag(name = "queue", description = "전적 검색 대기열 대기표 (대기열 문서 5.2)")
@RestController
public class QueueController {

    private final TicketRegistry registry;

    public QueueController(TicketRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "대기표 상태",
            description = """
                    status: waiting(줄에 있음) · running(수집·코멘트 생성 중) · done(결과 준비됨) · failed(실패).
                    position(waiting일 때만) = 내 앞 작업 수(진행 중 포함) + 1 — 화면의 "앞에 N명"은 position − 1.
                    묻는 것 자체가 "아직 기다리는 중" 표시 — 30초 동안 묻지 않으면 대기표를 버린다.
                    done이면 원래 요청(stats · comment)을 다시 부르면 캐시에서 즉시 온다. failed면 error_code가 원래 냈을 오류.""")
    @ApiResponse(responseCode = "200", description = "대기표 상태",
            content = @Content(schema = @Schema(implementation = QueueTicketResponse.class)))
    @ApiResponse(responseCode = "404", description = "TICKET_NOT_FOUND — 없는(만료된) 대기표. 처음부터 다시 검색",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @GetMapping("/api/v1/queue/{ticketId}")
    public QueueTicketResponse get(@Parameter(description = "202 본문의 ticket_id") @PathVariable String ticketId) {
        return registry.view(ticketId);
    }

    @Operation(summary = "대기표 취소",
            description = "대기 화면에서 \"취소\" — 내 대기표만 지운다. 같은 작업을 바라보는 다른 대기표가 없고 아직 시작 전이면 작업도 줄에서 뺀다.")
    @ApiResponse(responseCode = "204", description = "취소됨")
    @ApiResponse(responseCode = "404", description = "TICKET_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @DeleteMapping("/api/v1/queue/{ticketId}")
    public ResponseEntity<Void> cancel(@PathVariable String ticketId) {
        registry.cancel(ticketId);
        return ResponseEntity.noContent().build();
    }
}
