package com.finesse.backend.calc.service;

import com.finesse.backend.calc.calculator.*;
import com.finesse.backend.calc.collector.*;
import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoUserNotFoundException;
import com.finesse.backend.calc.preprocessing.MatchPreprocessor;
import com.finesse.backend.calc.preprocessing.MatchScopedPseudonymizer;
import com.finesse.backend.calc.preprocessing.MatchValidator;
import com.finesse.backend.calc.preprocessing.NicknamePseudonymizer;
import com.finesse.backend.calc.service.AnalysisOutcome.ColdStartReason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.finesse.backend.calc.fixture.RawMatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatCalculatorFacadeTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    /** gamesPlayed와 매치 목록을 돌려주는 가짜 API. notFound/fail이면 예외를 던진다. */
    private static TetrIoApi api(int gamesPlayed, List<RawMatch> matches, boolean notFound, boolean fail) {
        return new TetrIoApi() {
            @Override
            public UserSummary fetchLeagueSummary(String username, String sessionId) {
                if (notFound) throw new TetrIoUserNotFoundException(username);
                return new UserSummary(username, "s", 15000, 2000, 60, null, gamesPlayed);
            }

            @Override
            public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
                if (fail) throw new TetrIoApiException("테스트용 실패");
                return new RecordPage(afterCursor == null ? matches : List.of(), 0, null);
            }
        };
    }

    private static StatCalculatorFacade facade(TetrIoApi api) {
        CollectorProperties cp = new CollectorProperties("http://unused", Duration.ZERO,
                3, 100, 365, 30, 3, Duration.ofSeconds(3), Duration.ofSeconds(5));
        AnalyticsProperties ap = new AnalyticsProperties(10, 300, 40);
        CalculatorRegistry registry = new CalculatorRegistry(List.of(
                new FancyMathCalculator(), new DeltaStatCalculator(), new HighlightStatCalculator(),
                new RecentWinLossCalculator(ap), new ProfileWindowDeltaCalculator(), new RivalryCalculator()));
        return new StatCalculatorFacade(
                new TetrIoCollector(api, cp),
                new MatchPreprocessor(new MatchValidator(), new MatchScopedPseudonymizer()),
                registry, new NicknamePseudonymizer(), ap, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** n판(1시간 간격), 앞의 invalid판은 결과가 "draw"라 정제에서 제외된다. 상대는 두 명이 번갈아 나온다. */
    private static List<RawMatch> matches(int n, int invalid) {
        List<RawMatch> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RawMatch m = i < invalid
                    ? raw("m" + i, 0, "draw", me(), opponent("uid-" + (i % 2), "rival"), rounds(true))
                    : valid("m" + i, 0, "uid-" + (i % 2), i % 2 == 0 ? "alphaone" : "bravotwo");
            list.add(new RawMatch(m.matchId(), NOW.minus(Duration.ofHours(i + 1)),
                    m.result(), m.me(), m.opponent(), m.rounds()));
        }
        return list;
    }

    @Test
    void 존재하지_않는_유저는_UserNotFound이고_이름은_소문자로_정규화한다() {
        AnalysisOutcome outcome = facade(api(0, List.of(), true, false)).analyze(" SomeUser ");

        assertThat(outcome).isEqualTo(new AnalysisOutcome.UserNotFound("someuser"));
    }

    @Test
    void 누적_판수가_10판_미만이면_매치를_받지_않고_Cold_Start다() {
        AnalysisOutcome outcome = facade(api(5, List.of(), false, false)).analyze("user");

        assertThat(outcome).isInstanceOfSatisfying(AnalysisOutcome.ColdStartBypass.class,
                c -> assertThat(c.reason()).isEqualTo(ColdStartReason.FEW_GAMES_TOTAL));
    }

    @Test
    void 일년_이내_판수가_10판_미만이면_Cold_Start다() {
        AnalysisOutcome outcome = facade(api(50, matches(8, 0), false, false)).analyze("user");

        assertThat(outcome).isInstanceOfSatisfying(AnalysisOutcome.ColdStartBypass.class, c -> {
            assertThat(c.reason()).isEqualTo(ColdStartReason.FEW_GAMES_IN_YEAR);
            assertThat(c.availableMatches()).isEqualTo(8);
        });
    }

    @Test
    void 정제_후_10판_미만이면_비정상_매치_사유의_Cold_Start다() {
        AnalysisOutcome partlyInvalid = facade(api(50, matches(12, 5), false, false)).analyze("user");
        AnalysisOutcome allInvalid = facade(api(50, matches(12, 12), false, false)).analyze("user");

        assertThat(partlyInvalid).isInstanceOfSatisfying(AnalysisOutcome.ColdStartBypass.class, c -> {
            assertThat(c.reason()).isEqualTo(ColdStartReason.TOO_MANY_INVALID);
            assertThat(c.availableMatches()).isEqualTo(7);
        });
        assertThat(allInvalid).isInstanceOfSatisfying(AnalysisOutcome.ColdStartBypass.class,
                c -> assertThat(c.reason()).isEqualTo(ColdStartReason.TOO_MANY_INVALID));
    }

    @Test
    void 매치_수집이_실패하면_CollectionFailed다() {
        AnalysisOutcome outcome = facade(api(50, List.of(), false, true)).analyze("user");

        assertThat(outcome).isEqualTo(new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED));
    }

    @Test
    void 정상_분석은_6개_지표와_수집_정보를_돌려주고_상대는_마스킹_닉네임만_담는다() {
        AnalysisOutcome outcome = facade(api(50, matches(12, 0), false, false)).analyze("user");

        assertThat(outcome).isInstanceOfSatisfying(AnalysisOutcome.Analyzed.class, a -> {
            assertThat(a.meta().analyzedMatches()).isEqualTo(12);
            assertThat(a.meta().partial()).isFalse();
            assertThat(a.result().fancy().sampleCount()).isEqualTo(12);
            assertThat(a.result().recentWinLoss().recentCount()).isEqualTo(12);
            assertThat(a.result().profileWindowDelta().isAvailable()).isFalse(); // 이전 구간 없음
            assertThat(a.result().rivalryStats().rivalCount()).isEqualTo(2);
            assertThat(a.result().rivalryStats().rivals())
                    .extracting(RivalOpponentStatsNickname::of)
                    .containsExactly("alp**one", "bra**two");
        });
    }

    @Test
    void 빈_유저_이름은_거부한다() {
        assertThatThrownBy(() -> facade(api(50, List.of(), false, false)).analyze(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** extracting에 쓸 메서드 참조용 */
    private static final class RivalOpponentStatsNickname {
        static String of(com.finesse.backend.calc.domain.RivalOpponentStats s) {
            return s.maskedNickname();
        }
    }
}