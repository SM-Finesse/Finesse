package com.finesse.backend.calc.highlight;

import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 하이라이트 2단계 선정 (설계서 30.4절) */
class HighlightSelectorTest {

    private final HighlightSelector selector = new HighlightSelector(HighlightProperties.defaults());

    /** 11개 모두 NONE인 판정 목록에서 시작해 필요한 지표만 바꾼다 */
    private static List<JudgedMetric> base() {
        List<JudgedMetric> list = new ArrayList<>();
        for (String key : HighlightProperties.METRICS) {
            list.add(new JudgedMetric(key, HighlightJudge.groupOf(key), null, -1, 1, HighlightZone.NONE, null, null));
        }
        return list;
    }

    private static void set(List<JudgedMetric> list, String key, HighlightZone zone, Double deviation, Double edgeGap) {
        int i = HighlightProperties.METRICS.indexOf(key);
        list.set(i, new JudgedMetric(key, HighlightJudge.groupOf(key), 0.5, -1, 1, zone, deviation, edgeGap));
    }

    private static List<String> keys(List<JudgedMetric> picks) {
        return picks.stream().map(JudgedMetric::key).toList();
    }

    @Test
    void 같은_그룹_두번째는_절반으로_감점된다() {
        List<JudgedMetric> m = base();
        set(m, "delta_app", HighlightZone.HIGH, 2.0, null);
        set(m, "delta_weighted_app", HighlightZone.HIGH, 1.9, null);   // 같은 그룹 2위 → 0.95
        set(m, "strength_split", HighlightZone.LOW, -1.0, null);
        set(m, "delta_stride", HighlightZone.LOW, -0.96, null);

        assertThat(keys(selector.select(m))).containsExactly("delta_app", "strength_split", "delta_stride");
    }

    @Test
    void 점수가_같으면_고정_순서가_앞선_지표를_고른다() {
        List<JudgedMetric> m = base();
        set(m, "session_vs_slope", HighlightZone.HIGH, 1.0, null);
        set(m, "strength_split", HighlightZone.LOW, -1.0, null);
        set(m, "delta_opener", HighlightZone.HIGH, 1.0, null);
        set(m, "delta_cheese_index", HighlightZone.LOW, -1.0, null);

        assertThat(keys(selector.select(m)))
                .containsExactly("delta_opener", "delta_cheese_index", "strength_split");
    }

    @Test
    void 범위_밖이_모자라면_보통을_경계에_가까운_순으로_채운다() {
        List<JudgedMetric> m = base();
        set(m, "delta_app", HighlightZone.HIGH, 0.4, null);
        set(m, "delta_plonk", HighlightZone.NORMAL, 0.0, 0.30);
        set(m, "delta_vs_apm", HighlightZone.NORMAL, 0.0, 0.05);
        set(m, "strength_split", HighlightZone.NORMAL, 0.0, 0.05);

        assertThat(keys(selector.select(m))).containsExactly("delta_app", "delta_vs_apm", "strength_split");
    }

    @Test
    void 그래도_모자라면_있는_만큼만_고르고_NONE은_고르지_않는다() {
        List<JudgedMetric> m = base();
        set(m, "delta_comeback", HighlightZone.LOW, -3.0, null);

        assertThat(keys(selector.select(m))).containsExactly("delta_comeback");
        assertThat(selector.select(base())).isEmpty();
    }
}