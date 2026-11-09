package com.finesse.backend.calc.reliability;

import com.finesse.backend.calc.calculator.DeltaStatCalculator;
import com.finesse.backend.calc.calculator.HighlightStatCalculator;
import com.finesse.backend.calc.config.ReliabilityProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchRound;
import com.finesse.backend.calc.domain.MetricCaution;
import com.finesse.backend.calc.domain.MetricReliability;
import com.finesse.backend.calc.domain.PseudonymId;
import com.finesse.backend.calc.domain.ReliabilityReason;
import com.finesse.backend.calc.domain.ReliabilityReport;
import com.finesse.backend.calc.domain.SamplePart;
import com.finesse.backend.calc.domain.StatResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 「지표 신뢰도 판정 기준」 1장 판정 과정 */
class ReliabilityJudgeTest {

    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    private final ReliabilityJudge judge = new ReliabilityJudge(ReliabilityProperties.defaults());

    // ── 지표 단위 표시 (1.2절) ───────────────────────────────────────

    @Test
    void 지표_단위_표시는_R300으로_정한다() {
        assertThat(judge.caution("delta_app")).isEqualTo(MetricCaution.NONE);
        assertThat(judge.caution("delta_opener")).isEqualTo(MetricCaution.NONE);
        assertThat(judge.caution("strength_split")).isEqualTo(MetricCaution.INTERPRETATION_CAUTION);
        assertThat(judge.caution("session_vs_slope")).isEqualTo(MetricCaution.INTERPRETATION_CAUTION);
        assertThat(judge.caution("delta_comeback")).isEqualTo(MetricCaution.UNRELIABLE);
    }

    // ── strength_split (1.4·1.7절, 5장) ──────────────────────────────

    @Test
    void 문서_예시_120판은_해석_주의만_붙고_라벨은_없다() {
        // 1.7절: Q1·Q5 각 24판, Q1 18승·Q5 8승, 같은 상대 최대 2판, 상한 미도달
        MetricReliability r = run(strengthMatches(120, 18, 8, 0), false).metric("strength_split");

        assertThat(r.caution()).isEqualTo(MetricCaution.INTERPRETATION_CAUTION);
        assertThat(r.sampleInsufficient()).isFalse();
        assertThat(r.suspiciousData()).isFalse();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.sampleSizes().get(SamplePart.Q1)).isEqualTo(24);
        assertThat(r.sampleSizes().get(SamplePart.Q5)).isEqualTo(24);
        assertThat(r.ciLow()).isCloseTo(-0.62, within(0.01));
        assertThat(r.ciHigh()).isCloseTo(-0.13, within(0.01));
        assertThat(r.threshold()).isNull();
        assertThat(r.reliability()).isNull();
        assertThat(r.outlierCount()).isNull();
    }

    @Test
    void 분위가_20판_미만이면_기준_표본_부족이고_상한_도달이면_붙이지_않는다() {
        // 1.7절: 90판이면 Q1·Q5 각 18판
        List<MatchHistory> matches = strengthMatches(90, 12, 6, 0);
        MetricReliability notCapped = run(matches, false).metric("strength_split");
        MetricReliability capped = run(matches, true).metric("strength_split");

        assertThat(notCapped.sampleInsufficient()).isTrue();
        assertThat(notCapped.reasons()).containsExactly(ReliabilityReason.BELOW_REFERENCE_SAMPLE);
        assertThat(capped.sampleInsufficient()).isFalse();
        assertThat(capped.reasons()).isEmpty();
    }

    @Test
    void 분위가_5판_미만이면_최소_표본_부족이고_2단계는_보지_않는다() {
        MetricReliability r = run(strengthMatches(20, 2, 1, 0), true).metric("strength_split");

        assertThat(r.sampleSizes().get(SamplePart.Q1)).isEqualTo(4);
        assertThat(r.reasons()).containsExactly(ReliabilityReason.BELOW_MIN_SAMPLE);
    }

    @Test
    void 같은_상대가_3판_이상이면서_10퍼센트_이상이면_의심_데이터다() {
        // 분모 48판: 같은 상대 4판(8.3%)은 해당 없음, 5판(10.4%)은 해당
        MetricReliability four = run(strengthMatches(120, 18, 8, 4), false).metric("strength_split");
        MetricReliability five = run(strengthMatches(120, 18, 8, 5), false).metric("strength_split");

        assertThat(four.suspiciousData()).isFalse();
        assertThat(five.suspiciousData()).isTrue();
        assertThat(five.sampleInsufficient()).isFalse();
        assertThat(five.reasons()).containsExactly(ReliabilityReason.OPPONENT_CONCENTRATION);
    }

    @Test
    void 같은_상대_기준은_3판_미만이면_비율이_높아도_해당하지_않는다() {
        // 20판: Q1·Q5 각 4판, 분모 8판 중 같은 상대 2판(25%)
        MetricReliability r = run(strengthMatches(20, 2, 1, 2), true).metric("strength_split");

        assertThat(r.suspiciousData()).isFalse();
    }

    // ── delta_comeback (1.2절) ───────────────────────────────────────

    @Test
    void 역전_지표는_신뢰_불가만_표시하고_라벨은_판정하지_않는다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            matches.add(rounds(i, i % 2 == 0 ? MatchResult.WIN : MatchResult.LOSE, false, false, true, true, true));
            matches.add(rounds(100 + i, i % 3 == 0 ? MatchResult.LOSE : MatchResult.WIN, true, true, false, false, false));
        }
        MetricReliability r = run(matches, false).metric("delta_comeback");

        assertThat(r.caution()).isEqualTo(MetricCaution.UNRELIABLE);
        assertThat(r.sampleInsufficient()).isFalse();
        assertThat(r.suspiciousData()).isFalse();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.sampleSizes().get(SamplePart.COMEBACK)).isEqualTo(6);
        assertThat(r.sampleSizes().get(SamplePart.COMEBACK_AGAINST)).isEqualTo(6);
        assertThat(r.ciLow()).isNotNull();
        assertThat(r.ciHigh()).isNotNull();
        assertThat(r.threshold()).isNull();
    }

    // ── Δ 8종 (1.4절, 2.2·2.7·2.10절) ────────────────────────────────

    @Test
    void Δ는_신뢰도가_목표_이상이면_라벨이_없고_근거_값을_돌려준다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            matches.add(delta(i, 60 + (i % 5), 1.0, 120, 60, 1.0, 120));
        }
        StatResult result = result(matches);
        MetricReliability r = judge.judge(result, matches, false, "user").metric("delta_app");

        assertThat(r.caution()).isEqualTo(MetricCaution.NONE);
        assertThat(r.sampleInsufficient()).isFalse();
        assertThat(r.reliability()).isGreaterThanOrEqualTo(0.9);
        assertThat(r.sampleSizes().get(SamplePart.ALL)).isEqualTo(100);
        double t = ReliabilityMath.t975(99);
        assertThat(r.threshold()).isCloseTo(t * 0.0661 * Math.sqrt(0.1 / 0.9), within(1e-12));
        assertThat(r.ciLow()).isCloseTo(result.delta().deltaApp() - r.errorMargin(), within(1e-12));
        assertThat(r.ciHigh()).isCloseTo(result.delta().deltaApp() + r.errorMargin(), within(1e-12));
        assertThat(r.outlierCount()).isZero();
    }

    @Test
    void Δ는_기복이_커서_신뢰도가_목표_미만이면_표본_부족이다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            matches.add(delta(i, i % 2 == 0 ? 30 : 110, 1.0, 120, 60, 1.0, 120));
        }
        MetricReliability r = run(matches, false).metric("delta_app");

        assertThat(r.sampleInsufficient()).isTrue();
        assertThat(r.reasons()).containsExactly(ReliabilityReason.RELIABILITY_BELOW_TARGET);
        assertThat(r.reliability()).isLessThan(0.9);
        assertThat(r.errorMargin()).isGreaterThan(r.threshold());
    }

    @Test
    void Δ는_이상치가_평균을_관측_오차보다_크게_움직이면_의심_데이터다() {
        // 100판 중 10판만 본인 APM이 크게 튄다 — 이상치를 빼면 평균이 오차 범위 밖으로 움직인다
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            matches.add(delta(i, i < 10 ? 160 : 60 + (i % 5) * 0.5, 1.0, 120, 60, 1.0, 120));
        }
        MetricReliability r = run(matches, false).metric("delta_app");

        assertThat(r.outlierCount()).isEqualTo(10);
        assertThat(r.suspiciousData()).isTrue();
        assertThat(r.reasons()).contains(ReliabilityReason.OUTLIER_IMPACT);
    }

    @Test
    void 이상치가_있어도_평균을_오차_안에서만_움직이면_라벨이_없다() {
        List<MatchHistory> matches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            matches.add(delta(i, i == 0 ? 160 : 60 + (i % 5) * 0.5, 1.0, 120, 60, 1.0, 120));
        }
        MetricReliability r = run(matches, false).metric("delta_app");

        assertThat(r.outlierCount()).isEqualTo(1);
        assertThat(r.suspiciousData()).isFalse();
    }

    // ── session_vs_slope (1.4절, 2.6절, 5장) ─────────────────────────

    @Test
    void 기울기는_150판_미만이면_기준_표본_부족이고_구간은_같은_입력에서_항상_같다() {
        List<MatchHistory> matches = slopeMatches(40, 3);
        ReliabilityReport first = run(matches, false);
        ReliabilityReport second = run(matches, false);
        MetricReliability r = first.metric("session_vs_slope");

        assertThat(r.caution()).isEqualTo(MetricCaution.INTERPRETATION_CAUTION);
        assertThat(r.reasons()).containsExactly(ReliabilityReason.BELOW_REFERENCE_SAMPLE);
        assertThat(r.sampleSizes().get(SamplePart.ALL)).isEqualTo(40);
        assertThat(r.sampleSizes().get(SamplePart.ROUND_INDICES)).isEqualTo(3);
        assertThat(r.ciLow()).isEqualTo(second.metric("session_vs_slope").ciLow());
        assertThat(r.ciHigh()).isEqualTo(second.metric("session_vs_slope").ciHigh());
        assertThat(r.ciLow()).isLessThan(r.ciHigh());
        assertThat(run(matches, true).metric("session_vs_slope").reasons()).isEmpty();
    }

    @Test
    void 라운드_순번이_3개_미만이면_최소_표본_부족이다() {
        MetricReliability r = run(slopeMatches(200, 2), false).metric("session_vs_slope");

        assertThat(r.reasons()).containsExactly(ReliabilityReason.BELOW_MIN_SAMPLE);
    }

    @Test
    void 부트스트랩_구간은_양_끝_50번째_값이다() {
        List<MatchHistory> matches = slopeMatches(30, 3);
        double[] ci = judge.bootstrap(com.finesse.backend.calc.calculator.MetricSamples.roundSeries(matches),
                ReliabilityJudge.seed("user", "session_vs_slope"));
        Double value = HighlightStatCalculatorAccess.slope(matches);

        assertThat(ci[0]).isLessThanOrEqualTo(value);
        assertThat(ci[1]).isGreaterThanOrEqualTo(value);
        assertThat(ReliabilityJudge.seed("user", "session_vs_slope"))
                .isNotEqualTo(ReliabilityJudge.seed("other", "session_vs_slope"));
    }

    // ── 값이 없으면 판정하지 않는다 (1.6절) ──────────────────────────

    @Test
    void 지표_값이_null이면_신뢰도도_없다() {
        ReliabilityReport report = judge.judge(new StatResult(null, null, null, null, null, null, null),
                List.of(), false, "user");

        assertThat(report.metrics()).isEmpty();
        assertThat(report.metric("delta_app")).isNull();
        assertThat(report.version()).isEqualTo(ReliabilityProperties.DEFAULT_VERSION);
    }

    // ── 도우미 ───────────────────────────────────────────────────────

    private ReliabilityReport run(List<MatchHistory> matches, boolean capped) {
        return judge.judge(result(matches), matches, capped, "user");
    }

    private static StatResult result(List<MatchHistory> matches) {
        AnalyticsContext context = new AnalyticsContext(matches);
        return new StatResult(null, new DeltaStatCalculator().calculate(context),
                new HighlightStatCalculator().calculate(context), null, null, null, null);
    }

    /**
     * TR Gap이 큰 순(약한 상대 → 강한 상대)으로 total판을 만든다.
     * Q1(앞 20%)은 앞에서 q1Wins판, Q5(뒤 20%)는 앞에서 q5Wins판을 이긴다.
     * 상대는 2판마다 바뀌고(같은 상대 최대 2판), sameOpponent > 0이면 Q1 앞 sameOpponent판을 상대 9999로 둔다.
     */
    private static List<MatchHistory> strengthMatches(int total, int q1Wins, int q5Wins, int sameOpponent) {
        int quintile = total / 5;
        List<MatchHistory> list = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            boolean win;
            if (i < quintile) {
                win = i < q1Wins;
            } else if (i >= total - quintile) {
                win = i - (total - quintile) < q5Wins;
            } else {
                win = i % 2 == 0;
            }
            int opponent = i < sameOpponent ? 9999 : i / 2;
            list.add(match("s" + i, i, opponent, 20000.0, 19000.0 + i, win ? MatchResult.WIN : MatchResult.LOSE,
                    List.of(), 60, 1.0, 120, 3, false));
        }
        return list;
    }

    /** 라운드 순번 0~(indices−1), 라운드마다 VS가 매치별로 조금씩 다르게 떨어진다 */
    private static List<MatchHistory> slopeMatches(int total, int indices) {
        List<MatchHistory> list = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            List<MatchRound> rounds = new ArrayList<>();
            for (int r = 0; r < indices; r++) {
                rounds.add(new MatchRound(r, 200 - r * (i % 7) + (i % 3) * 4, 180, null, true, null));
            }
            list.add(match("r" + i, i, i, null, null, MatchResult.WIN, rounds, 60, 1.0, 120, 3, false));
        }
        return list;
    }

    private static MatchHistory delta(int i, double myApm, double myPps, double myVs,
                                      double oppApm, double oppPps, double oppVs) {
        return new MatchHistory("d" + i, BASE.plusSeconds(i), PseudonymId.of(i),
                myPps, myApm, myVs, oppPps, oppApm, oppVs,
                null, null, null, null, null, null, null, null,
                null, null, MatchResult.WIN, List.of(), 3, false);
    }

    private static MatchHistory rounds(int i, MatchResult result, boolean... wins) {
        List<MatchRound> rounds = new ArrayList<>();
        for (int r = 0; r < wins.length; r++) {
            rounds.add(new MatchRound(r, 0, 0, null, wins[r], null));
        }
        return match("c" + i, i, i, null, null, result, rounds, 60, 1.0, 120, 3, false);
    }

    private static MatchHistory match(String id, int seconds, int opponent, Double myTr, Double oppTr,
                                      MatchResult result, List<MatchRound> rounds,
                                      double apm, double pps, double vs, Integer firstTo, boolean endedEarly) {
        return new MatchHistory(id, BASE.plusSeconds(seconds), PseudonymId.of(opponent),
                pps, apm, vs, pps, apm, vs,
                null, null, null, null, null, null, null, null,
                myTr, oppTr, result, rounds, firstTo, endedEarly);
    }
}