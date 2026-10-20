package com.finesse.backend.calc.collector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** summaries/league 응답 → UserSummary 해석 (02 3.2절·부록 C.3, 08 49.1절 테스트 15, v4.1). */
class UserSummaryParseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private UserSummary parse(String json) {
        JsonNode data = mapper.readTree(json);
        return TetrIoApiClient.parseSummary("player", data);
    }

    @Test
    void 시즌_판수_승수_패수를_읽는다() {
        UserSummary s = parse("""
            {"gamesplayed":40,"gameswon":25,"glicko":2100.5,"rd":61.2,"tr":21000.1,"gxe":80.5,
             "rank":"x","apm":95.5,"pps":2.31,"vs":210.4}""");

        assertThat(s.gamesPlayed()).isEqualTo(40);
        assertThat(s.gamesWon()).isEqualTo(25);
        assertThat(s.gamesLost()).isEqualTo(15);
        assertThat(s.rank()).isEqualTo("x");
        assertThat(s.tr()).isEqualTo(21000.1);
        assertThat(s.apm()).isEqualTo(95.5);
    }

    @Test
    void gameswon이_없으면_승수는_0이다() {
        UserSummary s = parse("""
            {"gamesplayed":40,"glicko":-1,"rd":-1,"tr":-1,"gxe":-1,"rank":"z"}""");

        assertThat(s.gamesWon()).isEqualTo(0);
        assertThat(s.gamesLost()).isEqualTo(40);
        assertThat(s.apm()).isNull();
    }

    @Test
    void 승수가_판수보다_크면_패수는_0이다() {
        UserSummary s = parse("""
            {"gamesplayed":10,"gameswon":12,"glicko":1500,"rd":80,"tr":12000,"rank":"a"}""");

        assertThat(s.gamesLost()).isEqualTo(0);
    }

    @Test
    void 이전_생성자는_승수를_0으로_둔다() {
        UserSummary s = new UserSummary("player", "s", 15000, 2000, 60, null, 5, null, null, null);

        assertThat(s.gamesWon()).isEqualTo(0);
        assertThat(s.gamesLost()).isEqualTo(5);
    }
}
