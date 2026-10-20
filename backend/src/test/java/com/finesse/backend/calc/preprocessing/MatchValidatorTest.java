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
    void 본인이나_상대의_APM_0_PPS_0점2_미만_PPS_0점1_미만_VS_음수는_INVALID_STATS다() {
        assertThat(validator.validate(raw("m", 0, "victory",
                player("uid-me", "me", 0.0, 0.15, 120.0, 1000.0), opponent("u", "o"), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
        assertThat(validator.validate(raw("m", 0, "victory",
                me(), player("u", "o", -1.0, 1.0, 100.0, 1100.0), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
        assertThat(validator.validate(raw("m", 0, "victory",
                me(), player("u", "o", 50.0, 0.05, 100.0, 1100.0), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
        assertThat(validator.validate(raw("m", 0, "victory",
                me(), player("u", "o", 50.0, 1.0, -1.0, 1100.0), rounds(true))))
                .isEqualTo(MatchValidity.INVALID_STATS);
    }

    @Test
    void APM이_0이어도_PPS가_0점2_이상이면_정상_매치다() {
        // 블록은 계속 놓았지만 한 번도 공격하지 못하고 0:3으로 진 판 (5.7절, v3.5)
        assertThat(validator.validate(raw("m", 0, "defeat",
                player("uid-me", "me", 0.0, 0.2, 10.0, 1000.0), opponent("u", "o"),
                rounds(false, false, false))))
                .isEqualTo(MatchValidity.VALID);
        assertThat(validator.validate(raw("m", 0, "defeat",
                player("uid-me", "me", 0.0, 0.19, 10.0, 1000.0), opponent("u", "o"),
                rounds(false, false, false))))
                .isEqualTo(MatchValidity.INVALID_STATS);
    }

    @Test
    void 승자_승수가_3_5_7이_아니면_INCOMPLETE_MATCH다() {
        // 0·1·2·4·6승, 8승 이상 → 이탈 또는 데이터 훼손 (5.7·5.11절, v3.5)
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"), rounds(true, true))))
                .isEqualTo(MatchValidity.INCOMPLETE_MATCH);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                rounds(false, false, false, false, true))))                       // 0:4에서 상대 이탈 → 1승
                .isEqualTo(MatchValidity.INCOMPLETE_MATCH);
        assertThat(validator.validate(raw("m", 0, "defeat", me(), opponent("u", "o"),
                rounds(true, false, false, false, false))))                       // 상대 4승
                .isEqualTo(MatchValidity.INCOMPLETE_MATCH);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                rounds(true, true, true, true, true, true))))                     // 6승
                .isEqualTo(MatchValidity.INCOMPLETE_MATCH);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                rounds(true, true, true, true, true, true, true, true))))         // 8승
                .isEqualTo(MatchValidity.INCOMPLETE_MATCH);
        assertThat(MatchValidity.INCOMPLETE_MATCH.isReportedByApi()).isFalse();
    }

    @Test
    void 승자_승수가_3_5_7이면_통과한다() {
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                rounds(true, false, true, false, true))))
                .isEqualTo(MatchValidity.VALID);
        assertThat(validator.validate(raw("m", 0, "defeat", me(), opponent("u", "o"),
                rounds(false, false, false, false, false))))
                .isEqualTo(MatchValidity.VALID);
        assertThat(validator.validate(raw("m", 0, "victory", me(), opponent("u", "o"),
                rounds(true, true, true, true, true, true, true))))
                .isEqualTo(MatchValidity.VALID);
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
                List.of(new RawRound(true, false, null, 1.0, 1.0)))))
                .isEqualTo(MatchValidity.INSUFFICIENT_ROUND_DATA);
    }

    @Test
    void 매치_당시_TR이_없어도_제외하지_않는다() {
        assertThat(validator.validate(raw("m", 0, "defeat",
                player("uid-me", "me", 60.0, 1.0, 120.0, null),
                player("u", "o", 50.0, 1.0, 100.0, null), rounds(false, false, false))))
                .isEqualTo(MatchValidity.VALID);
    }
}