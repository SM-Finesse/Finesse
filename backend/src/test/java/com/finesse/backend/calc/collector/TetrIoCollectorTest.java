package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.finesse.backend.calc.fixture.RawMatchFixtures.valid;
import static org.assertj.core.api.Assertions.assertThat;

class TetrIoCollectorTest {

    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    /** 페이지 3개 × 2판 = 최대 6판(실제 설정의 300판에 해당), 부분 수집 최소 3판 */
    private static final CollectorProperties PROPS = new CollectorProperties(
            "http://unused", Duration.ZERO, 3, 2, 365, 3, 3, Duration.ofSeconds(3), Duration.ofSeconds(5));

    /** total판을 hoursStep 시간 간격으로 과거로 늘어놓고, failAtCall번째 호출에서 실패하는 가짜 API */
    private static TetrIoApi fakeApi(int total, long hoursStep, int failAtCall) {
        return new TetrIoApi() {
            int calls = 0;

            @Override
            public UserSummary fetchLeagueSummary(String username, String sessionId) {
                return null;
            }

            @Override
            public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
                calls++;
                if (calls == failAtCall) {
                    throw new TetrIoApiException("테스트용 실패");
                }
                int start = afterCursor == null ? 0 : Integer.parseInt(afterCursor);
                List<RawMatch> page = new ArrayList<>();
                for (int i = start; i < Math.min(total, start + limit); i++) {
                    RawMatch m = valid("m" + i, 0, "uid-" + (i % 3), "n");
                    page.add(new RawMatch(m.matchId(), NOW.minus(Duration.ofHours(hoursStep * (i + 1))),
                            m.result(), m.me(), m.opponent(), m.rounds()));
                }
                String next = start + limit < total ? String.valueOf(start + limit) : null;
                return new RecordPage(page, 0, next);
            }
        };
    }

    private CollectionResult collect(TetrIoApi api) {
        return new TetrIoCollector(api, PROPS).collectMatches("user", "session", NOW);
    }

    @Test
    void 최대_판수까지_최대_페이지_수만큼_받는다() {
        CollectionResult r = collect(fakeApi(20, 1, 0));

        assertThat(r.matches()).hasSize(6);
        assertThat(r.matches().get(0).matchId()).isEqualTo("m0");   // 최신순
        assertThat(r.requestedPages()).isEqualTo(3);
        assertThat(r.status()).isEqualTo(CollectionStatus.COMPLETE);
    }

    @Test
    void 매치가_적으면_있는_만큼만_받는다() {
        CollectionResult r = collect(fakeApi(5, 1, 0));

        assertThat(r.matches()).hasSize(5);
        assertThat(r.status()).isEqualTo(CollectionStatus.COMPLETE);
    }

    @Test
    void 일년보다_오래된_매치를_만나면_그_페이지에서_멈춘다() {
        // 100일 간격 → 1년 이내는 3판(100·200·300일 전)
        CollectionResult r = collect(fakeApi(20, 24 * 100, 0));

        assertThat(r.matches()).hasSize(3);
        assertThat(r.reachedCutoff()).isTrue();
        assertThat(r.requestedPages()).isEqualTo(2);
    }

    @Test
    void 첫_페이지가_실패하면_FAILED다() {
        assertThat(collect(fakeApi(20, 1, 1)).status()).isEqualTo(CollectionStatus.FAILED);
    }

    @Test
    void 중간_페이지가_실패하면_확보한_판수에_따라_PARTIAL_또는_INSUFFICIENT다() {
        CollectionResult partial = collect(fakeApi(20, 1, 3));       // 4판 확보 ≥ 3
        CollectionResult insufficient = collect(fakeApi(20, 1, 2));  // 2판 확보 < 3

        assertThat(partial.status()).isEqualTo(CollectionStatus.PARTIAL);
        assertThat(partial.matches()).hasSize(4);
        assertThat(insufficient.status()).isEqualTo(CollectionStatus.INSUFFICIENT);
    }

    @Test
    void 최대_판수를_채우면_다음_페이지를_호출하지_않는다() {
        // 4번째 호출에서 실패하도록 했지만, 6판(3페이지)을 채우면 더 부르지 않으므로 실패가 일어나지 않는다 (3.4절, v3.10)
        CollectionResult r = collect(fakeApi(20, 1, 4));

        assertThat(r.status()).isEqualTo(CollectionStatus.COMPLETE);
        assertThat(r.matches()).hasSize(6);
        assertThat(r.requestedPages()).isEqualTo(3);
        assertThat(r.failedPages()).isZero();
    }

    @Test
    void 같은_matchId는_한_번만_담는다() {
        TetrIoApi duplicated = new TetrIoApi() {
            @Override
            public UserSummary fetchLeagueSummary(String username, String sessionId) {
                return null;
            }

            @Override
            public RecordPage fetchRecentRecords(String username, String sessionId, String afterCursor, int limit) {
                RawMatch m = valid("same", 0, "uid-1", "n");
                RawMatch recent = new RawMatch("same", NOW.minusSeconds(10), m.result(), m.me(), m.opponent(), m.rounds());
                return new RecordPage(List.of(recent, recent), 0, afterCursor == null ? "1" : null);
            }
        };

        assertThat(collect(duplicated).matches()).hasSize(1);
    }
}