package com.finesse.backend.calc.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsPropertiesTest {

    @Test
    void TR_Trend_비율을_지정하지_않으면_0점3이다() {
        assertThat(new AnalyticsProperties(10, 300, 40).trTrendRatio()).isEqualTo(0.3);
        assertThat(new AnalyticsProperties(10, 300, 40, null).trTrendRatio()).isEqualTo(0.3);
    }

    @Test
    void TR_Trend_비율은_0점1에서_0점5_사이만_허용한다() {
        assertThat(new AnalyticsProperties(10, 300, 40, 0.1).trTrendRatio()).isEqualTo(0.1);
        assertThat(new AnalyticsProperties(10, 300, 40, 0.5).trTrendRatio()).isEqualTo(0.5);
        assertThatThrownBy(() -> new AnalyticsProperties(10, 300, 40, 0.09))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsProperties(10, 300, 40, 0.51))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
