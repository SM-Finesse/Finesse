package com.finesse.backend.calc.highlight;

import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 하이라이트 1·2단계 (설계서 30.3·30.4·30.8절, v4.3).
 * 정답값은 판정 규칙의 Python 참조 구현(Decimal)으로 계산한 값이다.
 * 유저 A·B는 인수인계 문서 5절의 실측 유저 두 명을 가명으로 바꾼 것이다.
 */
class HighlightJudgeTest {

    private final HighlightJudge judge = new HighlightJudge(HighlightProperties.defaults());
    private final HighlightSelector selector = new HighlightSelector(HighlightProperties.defaults());

    // ── 픽스처 ───────────────────────────────────────────────────────

    /** Δ 지표 8개(후보 순서: opener, plonk, stride, inf_ds, app, weighted_app, vs_apm, cheese) */
    private static DeltaStats delta(Double opener, Double plonk, Double stride, Double infDs,
                                    double app, double wApp, double vsApm, double cheese) {
        return new DeltaStats(100, 0, 0, 0, app, wApp, vsApm, cheese, opener, plonk, stride, infDs, null, null);
    }

    /** 하이라이트 지표 — 역전은 기회·성공·허용 기회·역전당함 수로 준다 */
    private static HighlightStats highlight(Double strength, int won, int opp, int allowed, int againstOpp,
                                            double slope) {
        Double rate = opp == 0 ? null : (double) won / opp;
        Double against = againstOpp == 0 ? null : (double) allowed / againstOpp;
        Double deltaComeback = rate == null || against == null ? null : rate - against;
        return new HighlightStats(null, strength, opp, won, rate, againstOpp, allowed, against, deltaComeback,
                slope, true, List.of());
    }

    private static final DeltaStats USER_A_DELTA = delta(0.11914979253914021, 0.04877875206834625,
            -0.18607387381410842, -0.12143466706643197, 0.09338222738527484, 0.04804871337775963,
            -0.04983442255315388, -15.573985319047166);
    private static final HighlightStats USER_A_HIGHLIGHT =
            highlight(-0.26315789473684215, 8, 20, 1, 25, 5.05363839306214);

    private static final DeltaStats USER_B_DELTA =
            delta(0.0864, 0.2386, -0.4435, -0.1352, 0.1123, 0.0702, -0.0515, -14.4482);
    private static final HighlightStats USER_B_HIGHLIGHT = highlight(-0.6667, 2, 15, 9, 30, 1.9057);

    private static JudgedMetric metric(HighlightJudgement j, String key) {
        return j.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> zonesAndDeviations(HighlightJudgement j) {
        return j.metrics().stream()
                .map(m -> m.zone().wireName() + (m.deviation() == null ? "" : " " + m.deviation()))
                .toList();
    }

    private HighlightJudgement judgeAndSelect(DeltaStats d, HighlightStats h, String rank) {
        HighlightJudgement j = judge.judge(d, h, rank);
        return j.withPicks(selector.select(j.metrics()));
    }

    private static List<String> pickKeys(HighlightJudgement j) {
        return j.picks().stream().map(JudgedMetric::key).toList();
    }

    // ── 정답 데이터 (30.8절) ─────────────────────────────────────────

    @Test
    void 유저_A는_기본_범위로_판정하고_역전_기울기_공격효율을_고른다() {
        HighlightJudgement j = judgeAndSelect(USER_A_DELTA, USER_A_HIGHLIGHT, null);

        assertThat(j.rangesVersion()).isEqualTo("ref-2026-10-15-g2");
        assertThat(j.rangeGroup()).isEqualTo("default");
        assertThat(zonesAndDeviations(j)).containsExactly(
                "normal 0.0", "normal 0.0", "low -0.07", "normal 0.0", "high 1.04", "high 0.42",
                "normal 0.0", "low -0.35", "normal 0.0", "high 2.7", "high 2.54");
        assertThat(pickKeys(j)).containsExactly("delta_comeback", "session_vs_slope", "delta_app");
    }

    @Test
    void 유저_B는_기본_범위로_판정하고_역전_공격효율_상대강도를_고른다() {
        HighlightJudgement j = judgeAndSelect(USER_B_DELTA, USER_B_HIGHLIGHT, null);

        assertThat(zonesAndDeviations(j)).containsExactly(
                "normal 0.0", "high 0.5", "low -0.8", "normal 0.0", "high 1.32", "high 0.85",
                "normal 0.0", "low -0.3", "low -1.06", "low -1.37", "high 0.37");
        assertThat(pickKeys(j)).containsExactly("delta_comeback", "delta_app", "strength_split");
    }

    @Test
    void 같은_값도_X플러스면_SS에서_X플러스_묶음_범위로_판정한다() {
        HighlightJudgement j = judgeAndSelect(USER_B_DELTA, USER_B_HIGHLIGHT, "X+");

        assertThat(j.rangeGroup()).isEqualTo("t5");
        assertThat(zonesAndDeviations(j)).containsExactly(
                "normal 0.0", "high 0.47", "low -0.72", "normal 0.0", "high 0.89", "high 0.51",
                "normal 0.0", "low -0.41", "low -0.08", "low -2.18", "normal 0.0");
        assertThat(pickKeys(j)).containsExactly("delta_comeback", "delta_app", "delta_stride");
        assertThat(metric(j, "session_vs_slope").q1()).isEqualTo(0.917);
    }

    // ── 범위 묶음 고르기 ─────────────────────────────────────────────

    @Test
    void 등급은_대소문자와_공백을_무시하고_Z와_모르는_값은_기본_범위다() {
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, " x+ ").rangeGroup()).isEqualTo("t5");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, "D").rangeGroup()).isEqualTo("t1");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, "s-").rangeGroup()).isEqualTo("t4");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, "z").rangeGroup()).isEqualTo("default");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, "").rangeGroup()).isEqualTo("default");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, "?").rangeGroup()).isEqualTo("default");
        assertThat(judge.judge(USER_B_DELTA, USER_B_HIGHLIGHT, null).rangeGroup()).isEqualTo("default");
    }

    // ── 경계값·반올림 (30.3절) ───────────────────────────────────────

    @Test
    void 값이_Q1이나_Q3와_같으면_보통이다() {
        HighlightJudgement atQ3 = judge.judge(delta(0.0, 0.0, 0.0, 0.0, 0.024, 0, 0, 0), null, null);
        HighlightJudgement atQ1 = judge.judge(delta(0.0, 0.0, 0.0, 0.0, -0.043, 0, 0, 0), null, null);

        assertThat(metric(atQ3, "delta_app").zone()).isEqualTo(HighlightZone.NORMAL);
        assertThat(metric(atQ3, "delta_app").deviation()).isEqualTo(0.0);
        assertThat(metric(atQ3, "delta_app").edgeGap()).isEqualTo(0.0);
        assertThat(metric(atQ1, "delta_app").zone()).isEqualTo(HighlightZone.NORMAL);
    }

    @Test
    void 범위_밖인데_반올림해_0이면_최소_0점01로_표시한다() {
        assertThat(metric(judge.judge(null, highlight(null, 0, 0, 0, 0, 1.375), null), "session_vs_slope")
                .deviation()).isEqualTo(0.01);
        assertThat(metric(judge.judge(null, highlight(null, 0, 0, 0, 0, -0.085), null), "session_vs_slope")
                .deviation()).isEqualTo(-0.01);
    }

    @Test
    void 소수_셋째_자리에서_HALF_UP으로_반올림한다() {
        assertThat(slopeDeviation(1.55125)).isEqualTo(0.13);    // d = 0.125
        assertThat(slopeDeviation(-0.26125)).isEqualTo(-0.13);  // d = −0.125
        assertThat(slopeDeviation(3.175105)).isEqualTo(1.24);   // d = 1.2449
        assertThat(HighlightJudge.shown(new BigDecimal("-0.0049"))).isEqualTo(-0.01);
    }

    private double slopeDeviation(double slope) {
        return metric(judge.judge(null, highlight(null, 0, 0, 0, 0, slope), null), "session_vs_slope").deviation();
    }

    @Test
    void 치즈_지표처럼_범위가_큰_지표도_같은_규칙이다() {
        JudgedMetric cheese = metric(judge.judge(delta(0.0, 0.0, 0.0, 0.0, 0, 0, 0, -15.574), null, null),
                "delta_cheese_index");

        assertThat(cheese.zone()).isEqualTo(HighlightZone.LOW);
        assertThat(cheese.deviation()).isEqualTo(-0.35);
    }

    // ── NONE ─────────────────────────────────────────────────────────

    @Test
    void 기울기가_정확히_0이면_판정하지_않고_값은_남긴다() {
        JudgedMetric slope = metric(judge.judge(null, highlight(null, 0, 0, 0, 0, 0.0), null), "session_vs_slope");

        assertThat(slope.zone()).isEqualTo(HighlightZone.NONE);
        assertThat(slope.value()).isEqualTo(0.0);
        assertThat(slope.deviation()).isNull();
    }

    @Test
    void 값이_없거나_NaN이면_NONE이고_값은_null이다() {
        HighlightJudgement noDelta = judge.judge(null, USER_A_HIGHLIGHT, null);
        HighlightJudgement nan = judge.judge(delta(Double.NaN, null, 0.0, 0.0, 0, 0, 0, 0), null, null);

        assertThat(noDelta.metrics().subList(0, 8)).allMatch(m -> m.zone() == HighlightZone.NONE && m.value() == null);
        assertThat(metric(nan, "delta_opener").zone()).isEqualTo(HighlightZone.NONE);
        assertThat(metric(nan, "delta_opener").value()).isNull();
        assertThat(metric(nan, "delta_plonk").zone()).isEqualTo(HighlightZone.NONE);
        assertThat(metric(nan, "strength_split").zone()).isEqualTo(HighlightZone.NONE);
    }

    // ── 역전 축소 (v4.3) ─────────────────────────────────────────────

    @Test
    void 역전은_기회가_적을수록_평균_쪽으로_당긴_축소값으로_판정한다() {
        // 2번 중 2번 역전(100%), 30번 중 3번 역전당함(10%) → 원값 0.90, 축소값 (2+2)/22 − (3+1.92)/50
        JudgedMetric comeback = metric(judge.judge(null, highlight(null, 2, 2, 3, 30, 1.0), null), "delta_comeback");

        assertThat(comeback.value()).isCloseTo(0.0834181818181818, within(1e-15));
        assertThat(comeback.zone()).isEqualTo(HighlightZone.HIGH);
        assertThat(comeback.deviation()).isEqualTo(0.92);
    }

    @Test
    void 역전_기회나_허용_기회가_0이면_NONE이다() {
        JudgedMetric noChance = metric(judge.judge(null, highlight(null, 0, 0, 3, 30, 1.0), null), "delta_comeback");
        JudgedMetric noLead = metric(judge.judge(null, highlight(null, 2, 5, 0, 0, 1.0), null), "delta_comeback");

        assertThat(noChance.zone()).isEqualTo(HighlightZone.NONE);
        assertThat(noChance.value()).isNull();
        assertThat(noLead.zone()).isEqualTo(HighlightZone.NONE);
    }

    @Test
    void 축소_계수가_0이면_원래_역전률_차이와_같다() {
        HighlightProperties k0 = new HighlightProperties(null, null, 0, null, null, null, null);
        JudgedMetric comeback = metric(new HighlightJudge(k0).judge(null, highlight(null, 2, 2, 3, 30, 1.0), null),
                "delta_comeback");

        assertThat(comeback.value()).isEqualTo(0.9);
    }
}