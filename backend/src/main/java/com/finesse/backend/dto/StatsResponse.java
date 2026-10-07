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
        Instant updatedAt, // TETR.IO에서 수집·계산한 시각 — 캐시 hit여도 이 값 그대로 ("N분 전 갱신" 표시용)
        Profile profile,
        FixedMetrics fixedMetrics,
        DeltaMetrics deltaMetrics,
        RoundCurves roundCurves,
        Rivals rivals,
        Map<String, Object> chapters // 헤비 뷰 8챕터 차트 데이터 — 세부 스키마 [협의 필요], 확정 전까지 자유 구조
) {

    // avatarUrl·xp·country·joinedAt은 /users/{username} 호출이 실패했거나 값이 없는 계정이면 null(응답에서 생략).
    // avatarUrl은 검색한 본인 것만 — 상대(라이벌) 사진은 닉네임 마스킹 원칙(FR-09)에 어긋나 내려주지 않는다.
    // playTimeSeconds는 유저가 숨기면 -1 그대로 (프론트가 tr·glicko·rd처럼 음수를 숨김).
    public record Profile(String rank, double tr, double glicko, double rd,
                          Double apm, Double pps, Double vs,
                          String avatarUrl, Double xp, String country, Instant joinedAt,
                          Double playTimeSeconds, Integer friendCount) {
    }

    // recentForm: 최근 최대 40경기 승패("W"/"L"), matches[0]이 최신이므로 index 0이 가장 최근 경기
    // winRate: 승패를 모르면 null(생략) — 0.0으로 보내면 "진짜 0%"와 "모름"이 구분되지 않는다(콜드스타트)
    public record FixedMetrics(Double winRate, List<Double> trTrend, List<String> recentForm) {
    }

    public record DeltaMetrics(
            Double trTrendDelta,
            PlaystyleRelative playstyleRelative,
            Attack attack,
            Defense defense,
            Double strengthSplit, // 매치 당시 TR이 있는 매치가 5판 미만이면 null (필드 자체 제외에 해당)
            Double comebackRate,
            Double comebackRateAgainst, // 2판 이상 앞서다 역전당한 비율 (API 명세서 4.1 표)
            // comeback_rate − comeback_rate_against (calc HighlightStats) — 라이트 하이라이트 후보 키.
            // 프론트가 LLM이 고른 stat의 근거 값을 delta_metrics에서 찾으므로 여기에도 둔다. 둘 중 하나라도 없으면 null
            Double deltaComeback,
            ComebackSamples comebackSamples,
            Double sessionVsSlope
    ) {
    }

    /**
     * 역전 지표의 표본 수 — heavy 06장 "몇 번 중 몇 번" 표시와 LLM 역전 챕터 입력용 (calc HighlightStats).
     * comebackRate = comebackWon ÷ comebackOpportunities, comebackRateAgainst = comebackAgainstAllowed ÷ comebackAgainstOpportunities.
     */
    public record ComebackSamples(int comebackOpportunities, int comebackWon,
                                  int comebackAgainstOpportunities, int comebackAgainstAllowed) {
    }

    // 계산 가능한 매치 비율이 낮으면 calc가 4개 모두 null로 준다 (LLM/AI 파트 설계 v1.2 5.1절)
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
