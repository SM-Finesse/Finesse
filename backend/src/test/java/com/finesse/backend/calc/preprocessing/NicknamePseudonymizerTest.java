package com.finesse.backend.calc.preprocessing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknamePseudonymizerTest {

    private final NicknamePseudonymizer masker = new NicknamePseudonymizer();

    @Test
    void 길이별로_가운데를_floor_길이_나누기_3개_최소_1개_마스킹한다() {
        assertThat(masker.pseudonymize("a")).isEqualTo("*");                 // 길이 1: 1개
        assertThat(masker.pseudonymize("ab")).isEqualTo("*b");               // 길이 2: 1개
        assertThat(masker.pseudonymize("abc")).isEqualTo("a*c");             // 길이 3: 1개
        assertThat(masker.pseudonymize("abcdef")).isEqualTo("ab**ef");       // 길이 6: 2개
        assertThat(masker.pseudonymize("player123")).isEqualTo("pla***123"); // 길이 9: 3개
    }

    @Test
    void 빈_문자열은_그대로_반환한다() {
        assertThat(masker.pseudonymize("")).isEmpty();
    }

    @Test
    void 결과에_원본_닉네임이_그대로_남지_않는다() {
        assertThat(masker.pseudonymize("player123")).isNotEqualTo("player123");
    }
}