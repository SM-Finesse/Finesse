package com.finesse.backend.calc.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 TETR.IO 호출 확인용. -Dtetrio.live=true -Dtetrio.username=<닉네임> 을 줄 때만 실행된다.
 * 실제 닉네임을 코드·테스트 데이터에 넣지 않는다(Public 레포).
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "tetrio.live", matches = "true")
class TetrIoApiLiveTest {

    @Autowired
    TetrIoCollector collector;

    @Test
    void 실제_유저의_요약과_매치를_받아온다() {
        String username = System.getProperty("tetrio.username");
        String session = TetrIoCollector.newSessionId();

        UserSummary summary = collector.fetchSummary(username, session);
        CollectionResult result = collector.collectMatches(username, session, Instant.now());

        System.out.printf("gamesPlayed=%d, 현재 구간=%d판, 이전 구간=%d판, 상태=%s, 버린 레코드=%d%n",
                summary.gamesPlayed(), result.matches().size(), result.previousWindowMatches().size(),
                result.status(), result.droppedRecords());
        assertThat(result.status()).isNotEqualTo(CollectionStatus.FAILED);
    }
}