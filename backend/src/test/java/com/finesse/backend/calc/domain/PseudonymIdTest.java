package com.finesse.backend.calc.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PseudonymIdTest {
    @Test
    void 시퀀스를_알파벳으로_변환한다() {
        assertThat(PseudonymId.of(0).value()).isEqualTo("User_A");
        assertThat(PseudonymId.of(25).value()).isEqualTo("User_Z");
        assertThat(PseudonymId.of(26).value()).isEqualTo("User_AA");
    }
}