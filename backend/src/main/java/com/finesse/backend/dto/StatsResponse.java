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
                          Double playTimeSeconds, Integer friendCount,
                          WindowDelta windowDelta) {
    }

    /**
     * 프로필 패널 5칸(TR·WR·APM·PPS·VS) 배지 — 최근 N판 vs 나머지 판의 변화율 %(9.4 = +9.4%) (calc ProfileWindowDeltaStats, 모듈 요청 10/8).
     * recentMatches = clamp(ceil(match_count × tr-trend-ratio), 3, 30). wrDeltaPct도 승률 차(%p)가 아니라 변화율(%)이다.
     * 비교할 나머지 판이 없거나 콜드스타트면 windowDelta 자체를 생략하고, trDeltaPct는 한쪽 구간에 TR이 없으면 단독 생략.
     */
    public record WindowDelta(int recentMatches, Double trDeltaPct, Double wrDeltaPct, Double apmDeltaPct,
                              Double ppsDeltaPct, Double vsDeltaPct) {
    }

    // recentForm: 최근 최대 40경기 승패("W"/"L"), matches[0]이 최신이므로 index 0이 가장 최근 경기
    // winRate: 승패를 모르면 null(생략) — 0.0으로 보내면 "진짜 0%"와 "모름"이 구분되지 않는다(콜드스타트)
    public record FixedMetrics(Double winRate, List<Double> trTrend, List<String> recentForm) {
    }

    public record DeltaMetrics(
            Double trTrendDelta,
            TrTrendBasis trTrendBasis, // tr_trend_delta의 근거 값 — TR이 있는 경기가 없으면 null
            PlaystyleRelative playstyleRelative,
            Attack attack,
            Defense defense,
            Double strengthSplit, // 매치 당시 TR이 있는 매치가 5판 미만이면 null (필드 자체 제외에 해당)
            List<StrengthQuintile> strengthQuintiles, // strength_split의 분위별 승률 Q1 → Q5, strength_split이 없으면 null
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
     * TR 추이 카드·heavy 01장의 근거 값 — calc tr_trend_delta와 같은 N으로 계산 (모듈 요청 10/7).
     * recentMatches = clamp(ceil(totalMatches × tr-trend-ratio), 3, 30)이고 totalMatches보다 클 수 없다.
     * totalMatches는 매치 당시 TR이 있는 경기 수(= fixed_metrics.tr_trend 길이)라 match_count와 다를 수 있다.
     * recentAvgTr − overallAvgTr = tr_trend_delta.
     */
    public record TrTrendBasis(int recentMatches, int totalMatches, double recentAvgTr, double overallAvgTr) {
    }

    /**
     * heavy "상대 강도별 승률" 차트의 막대 하나 (calc HighlightStats.StrengthQuintile, 모듈 요청 10/8).
     * quintile 1 = 가장 약한 상대 구간, 5 = 가장 강한 상대 구간. winRate = wins ÷ matches (0~1).
     */
    public record StrengthQuintile(int quintile, int matches, int wins, double winRate) {
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

    /**
     * 03장 공격 효율. myAvg·oppAvg는 heavy "나 vs 상대 평균" 막대용으로, Δ와 같은 매치 집합의 평균이라
     * myAvg − oppAvg = Δ다 (calc DeltaStats.mine·opp, 모듈 요청 10/8). LLM 입력에는 넣지 않는다(withoutAverages).
     */
    public record Attack(double deltaApp, double deltaWeightedApp, AttackAvg myAvg, AttackAvg oppAvg) {
        public Attack(double deltaApp, double deltaWeightedApp) {
            this(deltaApp, deltaWeightedApp, null, null);
        }

        /** LLM/AI 파트 설계 v1.2 스키마 그대로 — Δ 두 개만 */
        public Attack withoutAverages() {
            return new Attack(deltaApp, deltaWeightedApp);
        }
    }

    public record AttackAvg(double app, double weightedApp) {
    }

    /** 04장 수비·가비지. myAvg·oppAvg는 Attack과 같은 규칙 */
    public record Defense(double deltaVsApm, double deltaCheeseIndex, DefenseAvg myAvg, DefenseAvg oppAvg) {
        public Defense(double deltaVsApm, double deltaCheeseIndex) {
            this(deltaVsApm, deltaCheeseIndex, null, null);
        }

        /** LLM/AI 파트 설계 v1.2 스키마 그대로 — Δ 두 개만 */
        public Defense withoutAverages() {
            return new Defense(deltaVsApm, deltaCheeseIndex);
        }
    }

    public record DefenseAvg(double vsApm, double cheeseIndex) {
    }

    // samples: 그 라운드 순서의 라운드 수 — vs와 같은 길이, 값이 작을수록 평균이 흔들린다 (calc RoundPoint.samples)
    public record RoundCurves(List<Double> pps, List<Double> vs, List<Integer> samples) {
    }

    public record Rivals(List<RivalItem> items, int page, int pageSize, int total) {
    }

    public record RivalItem(String nicknameMasked, int matches, int wins, int losses, Instant lastMatchAt) {
    }
}
