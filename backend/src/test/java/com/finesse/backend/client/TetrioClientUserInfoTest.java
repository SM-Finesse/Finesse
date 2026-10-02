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
    void 배지_ts가_false거나_없으면_null이고_날짜면_읽는다() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"abc","ts":"2020-04-01T08:04:24.983Z","xp":1200.5,"gametime":-1,
                 "supporter":false,"supporter_tier":0,"friend_count":2438,
                 "badges":[
                   {"id":"kod_founder","label":"KO'd the founder","ts":"2020-06-27T00:53:37.657Z"},
                   {"id":"twc23_honorary","label":"TWC 2023","group":"twc23","ts":false},
                   {"id":"wpl_1","label":"WPL","desc":"winner"}
                 ]}"""));

        assertThat(u.joinedAt()).isEqualTo(Instant.parse("2020-04-01T08:04:24.983Z"));
        assertThat(u.gametime()).isEqualTo(-1.0); // 숨김(-1)은 그대로 — 프론트가 음수를 숨긴다
        assertThat(u.friendCount()).isEqualTo(2438);
        assertThat(u.badges()).hasSize(3);
        assertThat(u.badges().get(0).ts()).isEqualTo(Instant.parse("2020-06-27T00:53:37.657Z"));
        assertThat(u.badges().get(1).ts()).isNull();
        assertThat(u.badges().get(1).group()).isEqualTo("twc23");
        assertThat(u.badges().get(2).ts()).isNull();
        assertThat(u.badges().get(2).desc()).isEqualTo("winner");
    }

    @Test
    void 서포터와_가입일_없음() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"osk","xp":5000,"gametime":504013.13,"supporter":true,"supporter_tier":1,"friend_count":3760,
                 "badges":[{"id":"founder","label":"Founded TETR.IO"}]}"""));

        assertThat(u.joinedAt()).isNull();
        assertThat(u.supporter()).isTrue();
        assertThat(u.supporterTier()).isEqualTo(1);
        assertThat(u.gametime()).isEqualTo(504013.13);
        assertThat(u.badges()).extracting(TetrioClient.Badge::id).containsExactly("founder");
    }

    @Test
    void 날짜로_안_읽히는_가입일과_음수_xp는_null() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"kagari","ts":"not-a-date","xp":-1,"gametime":-1,"badges":[{"label":"id 없음"}]}"""));

        assertThat(u.joinedAt()).isNull();
        assertThat(u.xp()).isNull();
        assertThat(u.badges()).isEmpty(); // id 없는 배지는 버린다
        assertThat(u.supporter()).isNull();
        assertThat(u.friendCount()).isNull();
        assertThat(u.featuredAchievementKeys()).isEmpty();
    }

    @Test
    void 대표_업적_번호는_순서대로_읽는다() {
        TetrioClient.UserInfo u = TetrioClient.parseUserInfo(json("""
                {"_id":"turtle","achievements":[8,19,9,"x"]}"""));

        assertThat(u.featuredAchievementKeys()).containsExactly(8, 19, 9);
    }

    @Test
    void 대표_업적은_건_순서를_지키고_stub과_없는_번호는_뺀다() {
        // turtle 실제 응답(2026-10-02)을 줄인 것 — 응답 배열 순서와 유저가 건 순서([8, 19, 9])가 다르다
        JsonNode data = json("""
                [
                  {"k":9,"name":"10PC","object":"Clear 40 LINES using only All Clears","rank":5,"pos":4,"total":2325,"art":2},
                  {"k":8,"name":"20TSD","object":"Clear 40 LINES using only T-Spin Doubles","desc":"The ancient tradition","rank":5,"pos":3,"total":15121,"art":2},
                  {"k":19,"name":"The Emperor","rank":5,"pos":12,"total":13801,"art":2},
                  {"k":30,"name":"stub 업적","stub":true},
                  {"k":31,"name":"순위 없음","rank":1}
                ]""");

        var list = TetrioClient.parseFeaturedAchievements(data, java.util.List.of(8, 19, 9, 30, 31, 99));

        assertThat(list).extracting(TetrioClient.Achievement::k).containsExactly(8, 19, 9, 31);
        assertThat(list.get(0).name()).isEqualTo("20TSD");
        assertThat(list.get(0).pos()).isEqualTo(3);
        assertThat(list.get(1).object()).isNull();
        assertThat(list.get(3).pos()).isEqualTo(-1); // pos 없음 → -1
        assertThat(list.get(3).total()).isNull();
    }
}
