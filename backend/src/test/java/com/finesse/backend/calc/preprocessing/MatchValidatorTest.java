package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.collector.RawMatch.RawRound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.RawMatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class MatchValidatorTest {

    private final MatchValidator validator = new MatchValidator();

    @Test
    void 정상_매치는_VALID다() {
        assertThat(validator.validate(valid("m1", 0, "uid-1", "alpha"))).isEqualTo(MatchValidity.VALID);
    }

    @Test
    void 본인이나_상대의_APM_0_PPS_0점1_미만_VS_음수는_INVALID_STATS다() {
        assertThat(validator.validate(raw("m", 0, "victory",
                player("uid-me", "me", 0.0, 1.0, 120.0, 1000.0), opponent("u", "o"), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
        assertThat(validator.validate(raw("m", 0, "victory",
                me(), player("u", "o", 50.0, 0.05, 100.0, 1100.0), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
        assertThat(validator.validate(raw("m", 0, "victory",
                me(), player("u", "o", 50.0, 1.0, -1.0, 1100.0), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
    }

    @Test
    void 결과가_victory_defeat가_아니면_INVALID_STATS다() {
        assertThat(validator.validate(raw("m", 0, "draw", me(), opponent("u", "o"), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
    }

    @Test
    void 라운드가_없거나_라운드_VS가_비면_INSUFFICIENT_ROUND_DATA다() {
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"), List.of())))
                .isEqualTo(MatchValidity.INSUFFICIENT_ROUND_DATA);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"), null)))
                .isEqualTo(MatchValidity.INSUFFICIENT_ROUND_DATA);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                List.of(new RawRound(true, false, null, 1.0)))))
                .isEqualTo(MatchValidity.INSUFFICIENT_ROUND_DATA);
    }

    @Test
    void 매치_당시_TR이_없어도_제외하지_않는다() {
        assertThat(validator.validate(raw("m", 0, "defeat",
                player("uid-me", "me", 60.0, 1.0, 120.0, null),
                player("u", "o", 50.0, 1.0, 100.0, null), rounds(false))))
                .isEqualTo(MatchValidity.VALID);
    }
}