package com.finesse.backend.calc.config;

import com.finesse.backend.calc.config.HighlightProperties.Range;
import com.finesse.backend.calc.config.HighlightProperties.TierGroup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 하이라이트 설정 — 기본값·병합·시작 시 검사 (설계서 30.6절, v4.3) */
class HighlightPropertiesTest {

    private static Range r(String q1, String q3) {
        return new Range(new BigDecimal(q1), new BigDecimal(q3));
    }

    private static HighlightProperties withRanges(Map<String, Range> ranges) {
        return new HighlightProperties(null, null, null, null, null, ranges, null);
    }

    private static HighlightProperties withTiers(Map<String, TierGroup> tiers) {
        return new HighlightProperties(null, null, null, null, null, null, tiers);
    }

    @Test
    void 아무것도_적지_않으면_코드_기본값이다() {
        HighlightProperties p = HighlightProperties.defaults();

        assertThat(p.rangesVersion()).isEqualTo("ref-2026-10-15-g2");
        assertThat(p.groupPenalty()).isEqualTo(new BigDecimal("0.5"));
        assertThat(p.comebackShrinkK()).isEqualTo(20);
        assertThat(p.comebackPriorRate()).isEqualTo(new BigDecimal("0.100"));
        assertThat(p.comebackAgainstPriorRate()).isEqualTo(new BigDecimal("0.096"));
        assertThat(List.copyOf(p.ranges().keySet())).containsExactlyElementsOf(HighlightProperties.METRICS);
        assertThat(List.copyOf(p.tierRanges().keySet())).containsExactly("t1", "t2", "t3", "t4", "t5");
    }

    @Test
    void 기본값은_분석보고서_권장안_표와_같다() {
        HighlightProperties p = HighlightProperties.defaults();

        assertThat(p.ranges().get("delta_app")).isEqualTo(r("-0.043", "0.024"));
        assertThat(p.ranges().get("delta_comeback")).isEqualTo(r("-0.026", "0.031"));
        assertThat(p.tierRanges().get("t1").tiers()).containsExactly("d", "d+", "c-", "c");
        assertThat(p.tierRanges().get("t1").ranges().get("delta_cheese_index")).isEqualTo(r("0.505", "17.924"));
        assertThat(p.tierRanges().get("t3").ranges().get("session_vs_slope")).isEqualTo(r("0.023", "1.610"));
        assertThat(p.tierRanges().get("t5").tiers()).containsExactly("ss", "u", "x", "x+");
        assertThat(p.tierRanges().get("t5").ranges().get("strength_split")).isEqualTo(r("-0.644", "-0.374"));
        assertThat(p.tierRanges().get("t5").ranges().get("delta_comeback")).isEqualTo(r("-0.006", "0.039"));
    }

    @Test
    void 적은_지표만_덮어쓰고_키는_밑줄이_없어도_된다() {
        Map<String, Range> over = new LinkedHashMap<>();
        over.put("deltaapp", r("-0.05", "0.05"));          // Spring이 delta_app을 이렇게 바꿔 넘긴다
        over.put("session_vs_slope", r("0", "2"));

        HighlightProperties p = withRanges(over);

        assertThat(p.ranges().get("delta_app")).isEqualTo(r("-0.05", "0.05"));
        assertThat(p.ranges().get("session_vs_slope")).isEqualTo(r("0", "2"));
        assertThat(p.ranges().get("delta_opener")).isEqualTo(r("-0.249", "0.171"));
    }

    @Test
    void 묶음을_적으면_묶음에서_빠진_지표는_기본_범위를_쓴다() {
        Map<String, TierGroup> tiers = new LinkedHashMap<>();
        tiers.put("low", new TierGroup(List.of("D", "d+"), Map.of("delta_app", r("-0.1", "0"))));

        HighlightProperties p = withTiers(tiers);

        assertThat(List.copyOf(p.tierRanges().keySet())).containsExactly("low");
        assertThat(p.tierRanges().get("low").tiers()).containsExactly("d", "d+");
        assertThat(p.rangesFor("D+").name()).isEqualTo("low");
        assertThat(p.rangesFor("d+").ranges().get("delta_app")).isEqualTo(r("-0.1", "0"));
        assertThat(p.rangesFor("d+").ranges().get("delta_opener")).isEqualTo(r("-0.249", "0.171"));
        assertThat(p.rangesFor("x+").name()).isEqualTo("default");
    }

    @Test
    void 잘못된_값은_시작할_때_막는다() {
        assertThatThrownBy(() -> withRanges(Map.of("delta_unknown", r("0", "1"))))
                .hasMessageContaining("알 수 없는 지표");
        assertThatThrownBy(() -> r("0.1", "0.1")).hasMessageContaining("q1 < q3");
        assertThatThrownBy(() -> new HighlightProperties(null, BigDecimal.ZERO, null, null, null, null, null))
                .hasMessageContaining("group-penalty");
        assertThatThrownBy(() -> new HighlightProperties(null, null, -1, null, null, null, null))
                .hasMessageContaining("comeback-shrink-k");
        assertThatThrownBy(() -> new HighlightProperties(null, null, null, new BigDecimal("1.5"), null, null, null))
                .hasMessageContaining("comeback-prior-rate");
        assertThatThrownBy(() -> new HighlightProperties(" ", null, null, null, null, null, null))
                .hasMessageContaining("ranges-version");
    }

    @Test
    void 티어가_겹치거나_모르는_티어면_시작할_때_막는다() {
        Map<String, TierGroup> dup = new LinkedHashMap<>();
        dup.put("a", new TierGroup(List.of("d"), null));
        dup.put("b", new TierGroup(List.of("D"), null));
        Map<String, TierGroup> unknown = Map.of("a", new TierGroup(List.of("z"), null));
        Map<String, TierGroup> empty = Map.of("a", new TierGroup(List.of(), null));

        assertThatThrownBy(() -> withTiers(dup)).hasMessageContaining("두 묶음");
        assertThatThrownBy(() -> withTiers(unknown)).hasMessageContaining("알 수 없는 티어");
        assertThatThrownBy(() -> withTiers(empty)).hasMessageContaining("비어 있습니다");
    }
}