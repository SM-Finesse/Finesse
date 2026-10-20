package com.finesse.backend.calc.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PropertiesBindingTest {
    @Autowired AnalyticsProperties analytics;
    @Autowired CollectorProperties collector;
    @Autowired HighlightProperties highlight;

    @Test
    void yml_값이_바인딩된다() {
        assertThat(analytics.coldStartThreshold()).isEqualTo(10);
        assertThat(analytics.trTrendRatio()).isEqualTo(0.3);
        assertThat(collector.maxTotalMatches()).isEqualTo(300);
        assertThat(collector.maxAgeDays()).isEqualTo(365);
        assertThat(highlight.rangesVersion()).isEqualTo("ref-2026-10-15-g2");   // yml에 없으면 코드 기본값 (v4.3)
    }
}