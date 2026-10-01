package com.finesse.backend.calc.collector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RawMatchParserTest {

    private final RawMatchParser parser = new RawMatchParser();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final String PAGE = """
        {"entries":[
          {"_id":"m2","ts":"2026-09-20T10:00:00.000Z",
           "extras":{"result":"victory","league":{"uid-me":[{"tr":15000.5},{"tr":15010}],"uid-opp":[{"tr":15200},{"tr":15190}]}},
           "otherusers":[{"id":"uid-opp","username":"opp_now"}],
           "results":{
             "leaderboard":[{"id":"uid-me","username":"me_then","stats":{"apm":80.5,"pps":1.8,"vsscore":160.2}},
                            {"id":"uid-opp","username":"opp_then","stats":{"apm":70,"pps":1.6,"vsscore":140}}],
             "rounds":[[{"id":"uid-opp","alive":false,"stats":{"vsscore":130}},{"id":"uid-me","alive":true,"stats":{"vsscore":150}}],
                       [{"id":"uid-me","alive":false,"stats":{"vsscore":170}},{"id":"uid-opp","alive":true,"stats":{"vsscore":150}}]]},
           "p":{"pri":1,"sec":2,"ter":3}},
          {"_id":"m1","ts":"2026-09-19T10:00:00.000Z",
           "extras":{"result":"defeat","league":{"uid-me":[null,null],"uid-opp2":[null,null]}},
           "otherusers":[{"id":"uid-opp2","username":"x"}],
           "results":{
             "leaderboard":[{"id":"uid-me","username":"me_then","stats":{"apm":60,"pps":1.2,"vsscore":110}},
                            {"id":"uid-opp2","username":"opp2","stats":{"apm":65,"pps":1.3,"vsscore":120}}],
             "rounds":[[{"id":"uid-me","alive":false,"stats":{"vsscore":100}},{"id":"uid-opp2","alive":true,"stats":{"vsscore":120}}]]},
           "p":{"pri":4,"sec":5,"ter":6}},
          {"_id":"broken","ts":"not-a-date","otherusers":[{"id":"o"}],
           "results":{"leaderboard":[{"id":"a"},{"id":"o"}]},"p":{"pri":7,"sec":8,"ter":9}}
        ]}""";

    private RecordPage parse(String json) {
        JsonNode data = mapper.readTree(json);
        return parser.parsePage(data);
    }

    @Test
    void 본인과_상대를_otherusers_ID로_구분하고_매치_당시_닉네임과_매치_전_TR을_읽는다() {
        RawMatch m = parse(PAGE).matches().get(0);

        assertThat(m.matchId()).isEqualTo("m2");
        assertThat(m.result()).isEqualTo("victory");
        assertThat(m.me().userId()).isEqualTo("uid-me");
        assertThat(m.opponent().userId()).isEqualTo("uid-opp");
        assertThat(m.opponent().usernameAtMatch()).isEqualTo("opp_then");   // 현재 닉네임(opp_now)이 아님
        assertThat(m.me().trBefore()).isEqualTo(15000.5);                    // 매치 후(15010)가 아님
        assertThat(m.opponent().vs()).isEqualTo(140.0);
    }

    @Test
    void 라운드는_ID로_본인_상대를_찾고_alive로_승패를_정한다() {
        RawMatch m = parse(PAGE).matches().get(0);

        assertThat(m.rounds()).hasSize(2);
        assertThat(m.rounds().get(0).meAlive()).isTrue();
        assertThat(m.rounds().get(0).myVs()).isEqualTo(150.0);
        assertThat(m.rounds().get(1).meAlive()).isFalse();
    }

    @Test
    void 매치_당시_TR이_null이면_trBefore도_null이다() {
        RawMatch m = parse(PAGE).matches().get(1);

        assertThat(m.me().trBefore()).isNull();
        assertThat(m.opponent().trBefore()).isNull();
    }

    @Test
    void 깨진_레코드는_버리고_세며_다음_커서는_마지막_레코드의_p다() {
        RecordPage page = parse(PAGE);

        assertThat(page.matches()).hasSize(2);
        assertThat(page.droppedRecords()).isEqualTo(1);
        assertThat(page.nextCursor()).isEqualTo("7:8:9");
    }

    @Test
    void entries가_비면_빈_페이지이고_커서가_없다() {
        RecordPage page = parse("{\"entries\":[]}");

        assertThat(page.matches()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }
}