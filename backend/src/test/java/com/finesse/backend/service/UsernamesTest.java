package com.finesse.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernamesTest {

    @Test
    void 공백을_자르고_소문자로_바꾼다() {
        assertThat(Usernames.normalize("  SunG1ow ")).isEqualTo("sung1ow");
        assertThat(Usernames.normalize("-error404-")).isEqualTo("-error404-");
        assertThat(Usernames.normalize("abc")).isEqualTo("abc");
        assertThat(Usernames.normalize("a234567890123456")).hasSize(16);
    }

    @Test
    void 시스템_언어가_터키어여도_I를_i로_바꾼다() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(Usernames.normalize("ICLY")).isEqualTo("icly"); // 기본 toLowerCase()면 "ıcly"
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void TETR_IO_규칙에_안_맞으면_400용_예외() {
        for (String bad : new String[]{null, "", "ab", "a234567890123456X", "a b", "a{b}", "한글이름", "a.b"}) {
            assertThatThrownBy(() -> Usernames.normalize(bad))
                    .as("입력: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
