package com.finesse.backend.calc.config;

import com.finesse.backend.calc.config.ReliabilityProperties.MetricConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReliabilityPropertiesTest {

    @Test
    void 값을_적지_않으면_KR_5250명_기본값이다() {
        ReliabilityProperties p = ReliabilityProperties.defaults();

        assertThat(p.version()).isEqualTo(ReliabilityProperties.DEFAULT_VERSION);
        assertThat(p.targetReliability()).isEqualTo(0.9);
        assertThat(p.unreliableBelow()).isEqualTo(0.5);
        assertThat(p.minQuintileMatches()).isEqualTo(5);
        assertThat(p.referenceQuintileMatches()).isEqualTo(20);
        assertThat(p.minRoundIndices()).isEqualTo(3);
        assertThat(p.referenceMatches()).isEqualTo(150);
        assertThat(p.opponentMinMatches()).isEqualTo(3);
        assertThat(p.opponentMinShare()).isEqualTo(0.10);
        assertThat(p.bootstrapIterations()).isEqualTo(2000);
        assertThat(p.outlierThreshold()).isEqualTo(3.5);
        assertThat(p.metrics().size()).isEqualTo(11);
        assertThat(p.constants("delta_app")).isEqualTo(new MetricConstants(0.0661, 1.45, 0.986));
        assertThat(p.constants("strength_split")).isEqualTo(new MetricConstants(0.129, 1.0, 0.664));
    }

    @Test
    void 지표_상수는_적은_지표만_덮어쓰고_키_표기는_가리지_않는다() {
        ReliabilityProperties p = new ReliabilityProperties("rel-test", null, null, null, null, null, null,
                null, null, null, null, Map.of("delta-app", new MetricConstants(0.07, 1.5, 0.99)));

        assertThat(p.version()).isEqualTo("rel-test");
        assertThat(p.constants("delta_app")).isEqualTo(new MetricConstants(0.07, 1.5, 0.99));
        assertThat(p.constants("delta_plonk")).isEqualTo(new MetricConstants(0.169, 1.48, 0.985));
    }

    @Test
    void 잘못된_값은_시작할_때_막는다() {
        assertThatThrownBy(() -> new ReliabilityProperties(null, 1.0, null, null, null, null, null,
                null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliabilityProperties(null, 0.4, 0.5, null, null, null, null,
                null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliabilityProperties(null, null, null, null, null, null, null,
                null, null, 39, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliabilityProperties(null, null, null, null, null, null, null,
                null, null, null, null, Map.of("unknown", new MetricConstants(1, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricConstants(0.1, 0.9, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricConstants(0.1, 1.0, 1.1)).isInstanceOf(IllegalArgumentException.class);
    }
}