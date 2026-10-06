package com.finesse.backend.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /users/{username} 응답 해석 — 실제 TETR.IO 응답에서 본 모양(2026-10-02, osk·czsmall0402·kagari)을 그대로 쓴다.
 * 값 모양이 예상과 달라도 예외 없이 그 값만 null이어야 한다(예외가 나면 /stats 전체가 500).
 */
class TetrioClientUserInfoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    @Test
    void 가입일_플레이_시간_친구_수를_읽고_숨긴_플레이_시간은_그대로() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"abc","ts":"2020-04-01T08:04:24.983Z","xp":1200.5,"gametime":-1,"friend_count":2438}"""));

        assertThat(u.joinedAt()).isEqualTo(Instant.parse("2020-04-01T08:04:24.983Z"));
        assertThat(u.gametime()).isEqualTo(-1.0); // 숨김(-1)은 그대로 — 프론트가 음수를 숨긴다
        assertThat(u.friendCount()).isEqualTo(2438);
        assertThat(u.xp()).isEqualTo(1200.5);
    }

    @Test
    void 가입일이_false로_오거나_없으면_null() {
        assertThat(TetrioClient.parseUserInfo(json("{\"_id\":\"a\",\"ts\":false}")).joinedAt()).isNull();
        assertThat(TetrioClient.parseUserInfo(json("{\"_id\":\"osk\",\"gametime\":504013.13}")).joinedAt()).isNull();
    }

    @Test
    void 날짜로_안_읽히는_가입일과_음수_xp는_null() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"kagari","ts":"not-a-date","xp":-1,"gametime":-1}"""));

        assertThat(u.joinedAt()).isNull();
        assertThat(u.xp()).isNull();
        assertThat(u.friendCount()).isNull();
    }
}
