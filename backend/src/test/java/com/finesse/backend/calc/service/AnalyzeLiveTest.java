package com.finesse.backend.calc.service;

import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.config.HighlightProperties.Range;
import com.finesse.backend.calc.config.HighlightProperties.ResolvedRanges;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import com.finesse.backend.calc.domain.StatResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 TETR.IO로 전체 분석 확인용. -Dtetrio.live=true -Dtetrio.username=<닉네임> 을 줄 때만 실행된다.
 * 유저 이름은 코드·로그에 남기지 않는다. 출력은 등급·판정·선정 결과만 쓴다.
 * v4.3 — API로 계산한 지표가 그대로 하이라이트 판정·선정(04 30장)에 쓰이는지 검사한다.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "tetrio.live", matches = "true")
class AnalyzeLiveTest {

    private static final MathContext MC = MathContext.DECIMAL64;

    @Autowired
    StatCalculatorFacade facade;

    @Autowired
    HighlightProperties properties;

    @Test
    void 실제_유저_분석과_하이라이트_판정() {
        AnalysisOutcome outcome = facade.analyze(System.getProperty("tetrio.username"));
        System.out.println("결과 유형: " + outcome.getClass().getSimpleName());
        assumeTrue(outcome instanceof AnalysisOutcome.Analyzed,
                "정상 분석(Analyzed)일 때만 하이라이트를 검사한다 (Cold Start·조회 실패는 판정 없음)");

        AnalysisOutcome.Analyzed analyzed = (AnalysisOutcome.Analyzed) outcome;
        String rank = analyzed.summary().rank();
        StatResult result = analyzed.result();
        HighlightJudgement j = result.highlightJudgement();
        assertNotNull(j, "정상 분석이면 highlightJudgement가 있어야 한다");

        // ① 범위 버전·묶음 — 요약 API의 등급으로 고른 묶음이어야 한다
        ResolvedRanges expected = properties.rangesFor(rank);
        assertEquals(properties.rangesVersion(), j.rangesVersion());
        assertEquals(expected.name(), j.rangeGroup(), "등급 " + rank + "의 범위 묶음");

        // ② 판정 11개, 고정 순서, 묶음 범위 사용
        List<JudgedMetric> metrics = j.metrics();
        assertEquals(HighlightProperties.METRICS.size(), metrics.size());
        for (int i = 0; i < metrics.size(); i++) {
            JudgedMetric m = metrics.get(i);
            assertEquals(HighlightProperties.METRICS.get(i), m.key(), "고정 순서 " + (i + 1));
            Range r = expected.ranges().get(m.key());
            assertEquals(r.q1().doubleValue(), m.q1(), 0.0, m.key() + " Q1");
            assertEquals(r.q3().doubleValue(), m.q3(), 0.0, m.key() + " Q3");
            checkZone(m);
        }

        // ③ 판정 값 = API로 계산한 지표 값
        DeltaStats d = result.delta();
        HighlightStats h = result.highlight();
        assertSameValue(d == null ? null : d.deltaOpener(), metric(j, "delta_opener"));
        assertSameValue(d == null ? null : d.deltaPlonk(), metric(j, "delta_plonk"));
        assertSameValue(d == null ? null : d.deltaStride(), metric(j, "delta_stride"));
        assertSameValue(d == null ? null : d.deltaInfDs(), metric(j, "delta_inf_ds"));
        assertSameValue(d == null ? null : d.deltaApp(), metric(j, "delta_app"));
        assertSameValue(d == null ? null : d.deltaWeightedApp(), metric(j, "delta_weighted_app"));
        assertSameValue(d == null ? null : d.deltaVsApm(), metric(j, "delta_vs_apm"));
        assertSameValue(d == null ? null : d.deltaCheeseIndex(), metric(j, "delta_cheese_index"));
        assertSameValue(h == null ? null : h.strengthSplit(), metric(j, "strength_split"));
        assertSameValue(h == null ? null : h.sessionVsSlope(), metric(j, "session_vs_slope"));
        checkComeback(h, metric(j, "delta_comeback"));

        // ④ 선정 — 최대 3개, 중복 없음, NONE 제외, 높음·낮음이 보통보다 앞
        List<JudgedMetric> picks = j.picks();
        long candidates = metrics.stream().filter(m -> m.zone() != HighlightZone.NONE).count();
        assertEquals(Math.min(3, candidates), picks.size(), "선정 수");
        assertEquals(picks.size(), new HashSet<>(picks.stream().map(JudgedMetric::key).toList()).size(), "중복 선정");
        boolean normalSeen = false;
        for (JudgedMetric p : picks) {
            assertTrue(metrics.contains(p), p.key() + "는 판정 목록에 있어야 한다");
            assertTrue(p.zone() != HighlightZone.NONE, p.key() + " NONE 선정");
            if (p.zone() == HighlightZone.NORMAL) {
                normalSeen = true;
            } else {
                assertTrue(!normalSeen, "높음·낮음은 보통으로 채운 지표보다 앞에 온다");
            }
        }

        print(rank, j, h);
    }

    // ── 검사 도우미 ──────────────────────────────────────────────────

    private static JudgedMetric metric(HighlightJudgement j, String key) {
        return j.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    /** 영역과 숫자가 서로 맞는지 (30.3절) */
    private static void checkZone(JudgedMetric m) {
        switch (m.zone()) {
            case NONE -> {
                assertNull(m.deviation(), m.key() + " NONE이면 deviation null");
                assertNull(m.edgeGap(), m.key() + " NONE이면 edgeGap null");
            }
            case NORMAL -> {
                assertNotNull(m.value(), m.key());
                assertEquals(0.0, m.deviation(), 0.0, m.key() + " NORMAL이면 0.0");
                assertNotNull(m.edgeGap(), m.key() + " NORMAL이면 edgeGap");
                assertTrue(m.value() >= m.q1() && m.value() <= m.q3(), m.key() + " 범위 안");
            }
            case HIGH -> {
                assertTrue(m.value() > m.q3(), m.key() + " Q3 초과");
                assertTrue(m.deviation() >= 0.01, m.key() + " 높음은 +0.01 이상");
                assertNull(m.edgeGap(), m.key());
            }
            case LOW -> {
                assertTrue(m.value() < m.q1(), m.key() + " Q1 미만");
                assertTrue(m.deviation() <= -0.01, m.key() + " 낮음은 −0.01 이하");
                assertNull(m.edgeGap(), m.key());
            }
        }
    }

    /** NaN·∞는 null로 판정하고, 그 밖에는 계산값을 그대로 쓴다 */
    private static void assertSameValue(Double computed, JudgedMetric m) {
        if (computed == null || computed.isNaN() || computed.isInfinite()) {
            assertNull(m.value(), m.key() + " 값 없음");
            assertEquals(HighlightZone.NONE, m.zone(), m.key());
        } else {
            assertEquals(computed, m.value(), 0.0, m.key() + " — API로 계산한 값과 같아야 한다");
        }
    }

    /** 역전은 HighlightStats의 기회 수로 만든 축소값으로 판정한다 (30.3절) */
    private void checkComeback(HighlightStats h, JudgedMetric m) {
        if (h == null || h.comebackOpportunities() <= 0 || h.comebackAgainstOpportunities() <= 0) {
            assertEquals(HighlightZone.NONE, m.zone(), "역전 기회가 0이면 NONE");
            return;
        }
        BigDecimal k = BigDecimal.valueOf(properties.comebackShrinkK());
        BigDecimal rate = BigDecimal.valueOf(h.comebackWon()).add(k.multiply(properties.comebackPriorRate(), MC), MC)
                .divide(BigDecimal.valueOf(h.comebackOpportunities()).add(k, MC), MC);
        BigDecimal against = BigDecimal.valueOf(h.comebackAgainstAllowed())
                .add(k.multiply(properties.comebackAgainstPriorRate(), MC), MC)
                .divide(BigDecimal.valueOf(h.comebackAgainstOpportunities()).add(k, MC), MC);
        assertEquals(rate.subtract(against, MC).doubleValue(), m.value(), 1e-12, "역전 축소값");
    }

    // ── 출력 (닉네임 없음) ────────────────────────────────────────────

    private static void print(String rank, HighlightJudgement j, HighlightStats h) {
        System.out.printf("등급 %s → 범위 묶음 %s (%s)%n", rank, j.rangeGroup(), j.rangesVersion());
        System.out.printf("%-20s %12s %18s  %-6s %6s%n", "지표", "값", "보통 범위", "영역", "정도");
        for (JudgedMetric m : j.metrics()) {
            System.out.printf("%-20s %12s %8.3f ~ %7.3f  %-6s %6s%n", m.key(),
                    m.value() == null ? "-" : String.format(Locale.ROOT, "%.4f", m.value()),
                    m.q1(), m.q3(), m.zone().wireName(),
                    m.deviation() == null ? "-" : String.format(Locale.ROOT, "%.2f", m.deviation()));
        }
        if (h != null) {
            System.out.printf("역전 원값 %s · 역전 %d/%d · 역전당함 %d/%d%n", h.deltaComeback(),
                    h.comebackWon(), h.comebackOpportunities(),
                    h.comebackAgainstAllowed(), h.comebackAgainstOpportunities());
        }
        for (int i = 0; i < j.picks().size(); i++) {
            JudgedMetric p = j.picks().get(i);
            System.out.printf("pick%d: %s (%s %.2f)%n", i + 1, p.key(), p.zone().wireName(), p.deviation());
        }
    }
}