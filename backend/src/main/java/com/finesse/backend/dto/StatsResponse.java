package com.finesse.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/stats/{username} 응답.
 * 필드 구성은 Finesse-API명세서 4.1절 "응답 필드" 표를 그대로 따른다.
 */
public record StatsResponse(
        String username,
        boolean coldStart,
        int matchCount,
        Profile profile,
        FixedMetrics fixedMetrics,
        DeltaMetrics deltaMetrics,
        RoundCurves roundCurves,
        Rivals rivals,
        Map<String, Object> chapters // 헤비 뷰 8챕터 차트 데이터 — 세부 스키마 [협의 필요], 확정 전까지 자유 구조
) {

    public record Profile(String rank, double tr, double glicko, double rd) {
    }

    // recentForm: 최근 최대 40경기 승패("W"/"L"), matches[0]이 최신이므로 index 0이 가장 최근 경기
    public record FixedMetrics(double winRate, List<Double> trTrend, List<String> recentForm) {
    }

    public record DeltaMetrics(
            Double trTrendDelta,
            PlaystyleRelative playstyleRelative,
            Attack attack,
            Defense defense,
            Double strengthSplit, // 구간당 10판 미만이면 null (필드 자체 제외에 해당)
            Double comebackRate,
            Double sessionVsSlope
    ) {
    }

    // TODO: statrank 정규화 공식이 팀 문서 어디에도 없음 (TetraStats 소스코드 참고 필요 — 데이터 명세서 v5 4절/8절).
    // 데이터 엔지니어링 담당자가 공식을 확정하기 전까지는 전부 null로 둔다 (0.0으로 채우면 "차이 없음"으로 오독될 수 있어 위험).
    public record PlaystyleRelative(Double deltaOpener, Double deltaPlonk, Double deltaStride, Double deltaInfDs) {
    }

    public record Attack(double deltaApp, double deltaWeightedApp) {
    }

    public record Defense(double deltaVsApm, double deltaCheeseIndex) {
    }

    public record RoundCurves(List<Double> pps, List<Double> vs) {
    }

    public record Rivals(List<RivalItem> items, int page, int pageSize, int total) {
    }

    public record RivalItem(String nicknameMasked, int matches, int wins, int losses, Instant lastMatchAt) {
    }
}
