package com.finesse.backend.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 부록 A1·A2 문장 틀 (LLM 연동 명세 light-v5.6d) — LLM 서버 대체 문장(app/light_comment.py)과 같은 문장이 나와야 한다.
 */
class LightCommentTemplatesTest {

    private static final Random RNG = new Random(1);

    /** 늘 첫 번째를 고른다 — 비교 표현 "상대 대비", 첫머리 "이번 분석에서 눈에 띄는 점은" */
    private static final Random FIRST = new Random() {
        @Override
        public int nextInt(int bound) {
            return 0;
        }
    };

    private static String withoutPhrase(String sentence) {
        for (String p : LightCommentTemplates.COMPARE_PHRASES) {
            if (sentence.startsWith(p + " ")) {
                return sentence.substring(p.length() + 1);
            }
        }
        return sentence;
    }

    @Test
    void 우열_지표는_비교_표현을_붙이고_값은_소수_둘째_자리() {
        String s = LightCommentTemplates.sentence("delta_app", 0.085, "높음", RNG);
        assertThat(withoutPhrase(s)).isEqualTo("블록당 공격력에서 뚜렷하게 앞섭니다(+0.09).");
        assertThat(s).isNotEqualTo(withoutPhrase(s)); // 비교 표현이 붙어 있다
        // −0.004는 반올림하면 0 — 부호 없이 0.00, "같은 수준"
        assertThat(withoutPhrase(LightCommentTemplates.sentence("delta_weighted_app", -0.004, "보통", RNG)))
                .isEqualTo("보정 공격 효율은 같은 수준입니다(0.00).");
        assertThat(withoutPhrase(LightCommentTemplates.sentence("delta_weighted_app", -0.012, "보통", RNG)))
                .isEqualTo("보정 공격 효율에서 밀리는 편입니다(-0.01).");
    }

    @Test
    void 가비지_처리_성향은_소수_첫째_자리_조사는_받침으로() {
        assertThat(withoutPhrase(LightCommentTemplates.sentence("delta_cheese_index", -14.53, "낮음", RNG)))
                .isEqualTo("가비지 처리 성향이 뚜렷하게 옅습니다(-14.5).");
        assertThat(withoutPhrase(LightCommentTemplates.sentence("delta_inf_ds", 0.4, "높음", RNG)))
                .isEqualTo("Infinite Downstack 성향이 뚜렷하게 짙습니다(+0.40).");
    }

    @Test
    void 영역과_부호가_반대면_방향은_부호대로_폭은_작은_편() {
        assertThat(withoutPhrase(LightCommentTemplates.sentence("delta_vs_apm", -0.05, "높음", RNG)))
                .isEqualTo("수비 비중이 조금 작은 편이지만 그 폭은 크지 않습니다(-0.05).");
        assertThat(LightCommentTemplates.sentence("session_vs_slope", 0.60, "낮음", RNG))
                .isEqualTo("라운드가 진행될수록 공방 능력이 오르기는 하지만 그 폭은 작은 편입니다(+0.60).");
        assertThat(LightCommentTemplates.sentence("strength_split", -0.10, "높음", RNG))
                .isEqualTo("강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편입니다(-0.10).");
    }

    @Test
    void 값이_0이면_같은_수준이고_최근_대전_상대들보다는_쓰지_않는다() {
        for (int i = 0; i < 50; i++) {
            String s = LightCommentTemplates.sentence("delta_plonk", 0.004, "보통", new Random(i));
            assertThat(s).doesNotStartWith("최근 대전 상대들보다").endsWith("Plonk 성향은 같은 수준입니다(0.00).");
        }
    }

    @Test
    void 비교_기준이_상대가_아닌_지표는_비교_표현_없이_역전_경향은_원값() {
        assertThat(LightCommentTemplates.sentence("strength_split", -0.62, "낮음", RNG))
                .isEqualTo("강한 상대 구간에서는 약한 상대 구간보다 승률이 크게 떨어집니다(-0.62).");
        assertThat(LightCommentTemplates.sentence("delta_comeback", 0.0356, "보통", RNG))
                .isEqualTo("역전승 경향이 역전패 경향보다 강한 편입니다(+0.0356).");
        assertThat(LightCommentTemplates.sentence("delta_comeback", 0.0, "보통", RNG))
                .isEqualTo("역전승 경향과 역전패 경향이 같은 수준입니다(0).");
        assertThat(LightCommentTemplates.sentence("session_vs_slope", 2.4, "높음", RNG))
                .isEqualTo("라운드가 진행될수록 공방 능력이 뚜렷하게 오릅니다(+2.40).");
    }

    @Test
    void 기본_코멘트_틀은_숫자_없이_첫머리_틀과_고로_잇는다() {
        // 명세 부록 A2 예 (블록당 공격력 +0.08 높음 · Opener 성향 +0.42 높음 · 수비 비중 −0.20 낮음)
        String s = LightCommentTemplates.summary(List.of("delta_app", "delta_opener", "delta_vs_apm"),
                List.of(0.08, 0.42, -0.2), List.of("높음", "높음", "낮음"), FIRST);
        assertThat(s).isEqualTo("이번 분석에서 눈에 띄는 점은 상대 대비 블록당 공격력에서 뚜렷하게 앞선다는 것입니다. "
                + "상대 대비 Opener 성향이 뚜렷하게 짙고, 상대 대비 수비 비중이 뚜렷하게 작은 편입니다.");

        assertThat(LightCommentTemplates.summary(List.of("strength_split"), List.of(-0.62), List.of("낮음"), FIRST))
                .isEqualTo("이번 분석에서 눈에 띄는 점은 강한 상대 구간에서는 약한 상대 구간보다 승률이 크게 떨어진다는 것입니다.");
        assertThat(LightCommentTemplates.summary(List.of("delta_vs_apm", "session_vs_slope"),
                List.of(-0.05, 0.6), List.of("보통", "낮음"), FIRST))
                .isEqualTo("이번 분석에서 눈에 띄는 점은 상대 대비 수비 비중이 작은 편이라는 것입니다. "
                        + "라운드가 진행될수록 공방 능력이 오르기는 하지만 그 폭은 작은 편입니다.");
    }

    @Test
    void 개별_77개_모두_끝말_표에_있어_기본_코멘트로_만들_수_있다() {
        List<String> stats = List.of("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds", "delta_app",
                "delta_weighted_app", "delta_vs_apm", "delta_cheese_index", "strength_split", "delta_comeback",
                "session_vs_slope");
        double[] values = {0.5, -0.5, 0.0};
        List<String> zones = List.of("높음", "보통", "낮음");
        for (String stat : stats) {
            for (double v : values) {
                for (String z : zones) {
                    assertThatCode(() -> LightCommentTemplates.summary(List.of(stat), List.of(v), List.of(z), RNG))
                            .as(stat + " " + v + " " + z).doesNotThrowAnyException();
                }
            }
        }
    }
}
