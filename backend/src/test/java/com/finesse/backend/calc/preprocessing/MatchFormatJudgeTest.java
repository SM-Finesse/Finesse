package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.preprocessing.MatchFormatJudge.Judgment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finesse.backend.calc.fixture.RawMatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 경기 형식·조기 종료 판정 (설계서 5.11절, 테스트 계획 49.2 #15~#20-2). */
class MatchFormatJudgeTest {

    // ── 형식 기준: 등급 1차, TR 2차 ──────────────────────

    @Test
    void 등급으로_형식을_정한다() {
        assertThat(MatchFormatJudge.formatOf("d", null)).isEqualTo(3);
        assertThat(MatchFormatJudge.formatOf("a+", null)).isEqualTo(3);
        assertThat(MatchFormatJudge.formatOf("s-", null)).isEqualTo(5);   // #20-2: S-는 5선승
        assertThat(MatchFormatJudge.formatOf("ss", null)).isEqualTo(5);
        assertThat(MatchFormatJudge.formatOf("u", null)).isEqualTo(7);
        assertThat(MatchFormatJudge.formatOf("X+", null)).isEqualTo(7);   // 대소문자 무관
    }

    @Test
    void 등급이_TR보다_우선한다() {
        // 등급 A인데 TR은 20,000 이상으로 잡힌 경우 → 등급 기준 3선승
        assertThat(MatchFormatJudge.formatOf("a", 21_000.0)).isEqualTo(3);
    }

    @Test
    void 등급이_없거나_순위권_밖이면_TR로_정한다() {
        assertThat(MatchFormatJudge.formatOf(null, 13_799.0)).isEqualTo(3);
        assertThat(MatchFormatJudge.formatOf("z", 13_800.0)).isEqualTo(5);   // #20-1
        assertThat(MatchFormatJudge.formatOf(null, 19_999.0)).isEqualTo(5);
        assertThat(MatchFormatJudge.formatOf(null, 20_000.0)).isEqualTo(7);
        assertThat(MatchFormatJudge.formatOf("z", null)).isNull();
        assertThat(MatchFormatJudge.formatOf(null, null)).isNull();
    }

    // ── 판정 규칙 6개 ────────────────────────────────────

    @Test
    void 규칙1_승자_승수가_한쪽_형식과_같으면_정상() {
        assertThat(MatchFormatJudge.judge(7, 7, 7)).isEqualTo(new Judgment(7, false));   // #15
        assertThat(MatchFormatJudge.judge(5, 5, 7)).isEqualTo(new Judgment(5, false));   // #18
    }

    @Test
    void 규칙2_승자_승수가_아는_형식_모두보다_작으면_조기_종료() {
        assertThat(MatchFormatJudge.judge(5, 7, 7)).isEqualTo(new Judgment(7, true));    // #16
        assertThat(MatchFormatJudge.judge(3, 7, null)).isEqualTo(new Judgment(7, true));
        assertThat(MatchFormatJudge.judge(3, 5, 7)).isEqualTo(new Judgment(5, true));    // 작은 형식 기준
    }

    @Test
    void 규칙3_승자_승수가_아는_형식_모두보다_크면_정상() {
        assertThat(MatchFormatJudge.judge(5, 3, 3)).isEqualTo(new Judgment(5, false));   // #18-1
    }

    @Test
    void 규칙4_두_형식_사이면_판단_불가() {
        assertThat(MatchFormatJudge.judge(5, 3, 7)).isEqualTo(new Judgment(null, false)); // #19
    }

    @Test
    void 규칙5_6_형식을_모르면_7승만_확정() {
        assertThat(MatchFormatJudge.judge(7, null, null)).isEqualTo(new Judgment(7, false)); // #20
        assertThat(MatchFormatJudge.judge(5, null, null)).isEqualTo(new Judgment(null, false));
        assertThat(MatchFormatJudge.judge(3, null, null)).isEqualTo(new Judgment(null, false));
    }

    // ── 전처리 연결 ──────────────────────────────────────

    @Test
    void 전처리가_등급과_TR로_판정해_MatchHistory에_기록한다() {
        MatchPreprocessor preprocessor = new MatchPreprocessor(new MatchValidator(), new MatchScopedPseudonymizer());
        // #17: 등급 없음, TR 22,000·21,000, 3:1 종료 → 7선승 조기 종료
        RawMatch early = raw("early", 0, "victory",
                player("uid-me", "me", 60.0, 1.0, 120.0, 22_000.0),
                player("uid-o", "o", 50.0, 1.0, 100.0, 21_000.0),
                rounds(true, false, true, true));
        // 둘 다 등급 X, 7:4 → 정상
        RawMatch full = raw("full", 1, "victory",
                player("uid-me", "me", 60.0, 1.0, 120.0, null, "x"),
                player("uid-o", "o", 50.0, 1.0, 100.0, null, "x"),
                rounds(true, false, true, false, true, false, true, false, true, true, true));

        List<MatchHistory> matches = preprocessor.preprocess(List.of(early, full)).matches();

        MatchHistory e = matches.stream().filter(m -> m.matchId().equals("early")).findFirst().orElseThrow();
        MatchHistory f = matches.stream().filter(m -> m.matchId().equals("full")).findFirst().orElseThrow();
        assertThat(e.firstTo()).isEqualTo(7);
        assertThat(e.endedEarly()).isTrue();
        assertThat(e.isComebackEligible()).isFalse();
        assertThat(f.firstTo()).isEqualTo(7);
        assertThat(f.endedEarly()).isFalse();
        assertThat(f.isComebackEligible()).isTrue();
    }
}
