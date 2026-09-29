package com.finesse.backend.controller;

import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/stats/{username} — Finesse-API명세서 4.1절.
 * 실제 TETR.IO 연동 + 계산 로직은 StatsService에 있음.
 */
@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/v1/stats/{username}")
    public StatsResponse getStats(@PathVariable String username,
                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return statsService.getStats(username, refresh);
    }
}
