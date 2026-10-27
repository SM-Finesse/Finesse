package com.finesse.backend.calc.reliability;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 「지표 신뢰도 판정 기준」 2장 공식 */
class ReliabilityMathTest {

    @Test
    void t_분포_975_분위수는_표의_값과_같다() {
        // 2.1절: n = 10 → 2.262, n = 30 → 2.045, n = 100 → 1.984
        assertThat(ReliabilityMath.t975(9)).isCloseTo(2.2622, within(1e-4));
        assertThat(ReliabilityMath.t975(29)).isCloseTo(2.0452, within(1e-4));
        assertThat(ReliabilityMath.t975(99)).isCloseTo(1.9842, within(1e-4));
        assertThat(ReliabilityMath.t975(1)).isCloseTo(12.7062, within(1e-4));
        assertThat(ReliabilityMath.t975(1000)).isCloseTo(1.9623, within(1e-4));
    }

    @Test
    void Wilson_구간은_10판_10승에도_폭이_있다() {
        // 2.4절: 10판 10승이면 약 [0.72, 1.0]
        double[] w = ReliabilityMath.wilson(10, 10, ReliabilityMath.Z95);

        assertThat(w[0]).isCloseTo(0.7225, within(1e-3));
        assertThat(w[1]).isEqualTo(1.0);
    }

    @Test
    void Newcombe_구간은_문서_예시와_같다() {
        // 1.7절: Q5 8/24, Q1 18/24 → 약 [−0.62, −0.13]
        double[] ci = ReliabilityMath.newcombe(8, 24, 18, 24, ReliabilityMath.Z95);

        assertThat(ci[0]).isCloseTo(-0.62, within(0.01));
        assertThat(ci[1]).isCloseTo(-0.13, within(0.01));
    }

    @Test
    void 수정_Z_점수는_튀는_값만_이상치로_본다() {
        BitSet out = ReliabilityMath.modifiedZOutliers(new double[] {1, 2, 2, 3, 3, 4, 100}, 3.5);

        assertThat(out.cardinality()).isEqualTo(1);
        assertThat(out.get(6)).isTrue();
    }

    @Test
    void 값의_절반_이상이_같아_MAD가_0이면_판정하지_않는다() {
        assertThat(ReliabilityMath.modifiedZOutliers(new double[] {5, 5, 5, 5, 9}, 3.5)).isNull();
    }

    @Test
    void 표본표준편차는_n_빼기_1로_나눈다() {
        assertThat(ReliabilityMath.sampleSd(new double[] {2, 4, 4, 4, 5, 5, 7, 9}))
                .isCloseTo(Math.sqrt(32.0 / 7), within(1e-12));
        assertThat(ReliabilityMath.sampleSd(new double[] {1})).isNaN();
    }
}