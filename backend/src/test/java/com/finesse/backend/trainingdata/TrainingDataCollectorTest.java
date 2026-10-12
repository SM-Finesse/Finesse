package com.finesse.backend.trainingdata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.AnalysisOutcome.ColdStartReason;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.service.TrainingPayloadBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TrainingDataCollectorTest {

    // 앱 설정(application.yml spring.jackson)과 같게 — SNAKE_CASE, null 생략
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @TempDir
    Path dir;

    private final TrainingPayloadBuilder builder = mock(TrainingPayloadBuilder.class);
    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-10T09:03:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();

    private static final AnalysisOutcome.Analyzed ANALYZED = new AnalysisOutcome.Analyzed(null, null, null);

    public static TrainingPayloadBuilder.Payloads samplePayloads() {
        LlmLightRequest light = new LlmLightRequest(
                new LlmLightRequest.FixedMetrics(0.62, List.of(21100.0, 21250.0)),
                new LlmLightRequest.DeltaMetrics(new StatsResponse.PlaystyleRelative(-0.05, 0.18, -0.09, 0.12),
                        new StatsResponse.Attack(0.02, 1.4), new StatsResponse.Defense(0.31, -2.1),
                        -0.08, null, 0.0));
        Map<String, Object> comeback = new LinkedHashMap<>();
        comeback.put("comeback_rate", 0.33);
        comeback.put("comeback_rate_against", 0.28);
        comeback.put("comeback_opportunities", 41);
        comeback.put("comeback_won", 13);
        comeback.put("comeback_against_opportunities", 24);
        comeback.put("comeback_against_allowed", 7);
        Map<String, LlmHeavyChapterRequest> heavy = new LinkedHashMap<>();
        heavy.put("tr_trend", new LlmHeavyChapterRequest("tr_trend", Map.of("tr_trend_delta", 128.4)));
        heavy.put("playstyle", new LlmHeavyChapterRequest("playstyle", new StatsResponse.PlaystyleRelative(-0.05, 0.18, -0.09, 0.12)));
        heavy.put("attack", new LlmHeavyChapterRequest("attack", new StatsResponse.Attack(0.02, 1.4)));
        heavy.put("defense", new LlmHeavyChapterRequest("defense", new StatsResponse.Defense(0.31, -2.1)));
        heavy.put("strength_split", new LlmHeavyChapterRequest("strength_split", Map.of()));
        heavy.put("comeback_rate", new LlmHeavyChapterRequest("comeback_rate", comeback));
        heavy.put("session_vs_slope", new LlmHeavyChapterRequest("session_vs_slope", Map.of("session_vs_slope", 0.0)));
        return new TrainingPayloadBuilder.Payloads(light, heavy, 183);
    }

    private TrainingDataCollector collector() {
        TrainingCollectorProperties props = new TrainingCollectorProperties(
                dir, dir.resolve("targets.csv"), 60, 300, 3, "abc1234", false);
        return new TrainingDataCollector(builder, MAPPER, props, clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        }, dir);
    }

    private void targets(String... names) throws IOException {
        Files.writeString(dir.resolve("targets.csv"), CsvFiles.BOM + "username\n" + String.join("\n", names) + "\n",
                StandardCharsets.UTF_8);
    }

    private List<List<String>> usersCsv() throws IOException {
        List<List<String>> rows = CsvFiles.read(dir.resolve("users.csv"));
        return rows.subList(1, rows.size());
    }

    @Test
    void 결과별로_users_csv에_기록하고_성공한_유저만_JSON_8개를_남긴다() throws IOException {
        targets("Icly", "sung1ow", "newbie", "ghost", "x", "messy", "allbad");
        when(builder.analyze("icly")).thenReturn(ANALYZED);
        when(builder.payloads("icly", ANALYZED)).thenReturn(samplePayloads());
        when(builder.analyze("sung1ow")).thenReturn(new AnalysisOutcome.ColdStartBypass(null, 4, ColdStartReason.FEW_GAMES_IN_YEAR, null));
        when(builder.analyze("newbie")).thenReturn(new AnalysisOutcome.ColdStartBypass(null, 7, ColdStartReason.FEW_GAMES_TOTAL, null));
        when(builder.analyze("ghost")).thenReturn(new AnalysisOutcome.UserNotFound("ghost"));
        when(builder.analyze("messy")).thenReturn(new AnalysisOutcome.ColdStartBypass(null, 6, ColdStartReason.TOO_MANY_INVALID, null));
        when(builder.analyze("allbad")).thenReturn(new AnalysisOutcome.ColdStartBypass(null, 0, ColdStartReason.TOO_MANY_INVALID, null));

        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_DONE);

        assertThat(usersCsv()).extracting(r -> r.get(0) + " " + r.get(2) + " " + r.get(3) + " " + r.get(4)).containsExactly(
                "u0001 ok  183",
                "u0002 insufficient 2단계 1년내 4판 ",
                "u0003 insufficient 1단계 누적 7판 ",
                "u0004 not_found 404 ",
                "u0005 not_found 닉네임 형식 오류 ",
                "u0006 insufficient 3단계 정제후 6판 ",
                "u0007 insufficient 3단계 정제후 0판(전부 제외) ");
        assertThat(usersCsv().get(0).get(1)).isEqualTo("2026-10-10T18:03:00+09:00");
        try (var files = Files.list(dir.resolve("collected"))) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted()).containsExactly(
                    "u0001_heavy_attack.json", "u0001_heavy_comeback_rate.json", "u0001_heavy_defense.json",
                    "u0001_heavy_playstyle.json", "u0001_heavy_session_vs_slope.json",
                    "u0001_heavy_strength_split.json", "u0001_heavy_tr_trend.json", "u0001_light.json");
        }
        verify(builder, never()).analyze("x"); // 형식이 틀린 이름은 TETR.IO를 부르지 않는다
        // 직전 수집이 끝난 뒤 1분 — API를 부른 6명 사이 5번
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    @Test
    void JSON에는_메타와_LLM에_보내려던_요청이_그대로_들어간다() throws IOException {
        targets("icly");
        TrainingPayloadBuilder.Payloads payloads = samplePayloads();
        when(builder.analyze("icly")).thenReturn(ANALYZED);
        when(builder.payloads("icly", ANALYZED)).thenReturn(payloads);

        collector().run();

        JsonNode light = MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_light.json")));
        assertThat(light.path("user_key").asString()).isEqualTo("u0001");
        assertThat(light.path("collected_at").asString()).isEqualTo("2026-10-10T18:03:00+09:00");
        assertThat(light.path("calc_commit").asString()).isEqualTo("abc1234");
        assertThat(light.path("match_count").asInt()).isEqualTo(183);
        // WebClient가 LLM에 보내는 본문(같은 JsonMapper 직렬화)과 같아야 한다 — null 후보는 키째 빠진다
        assertThat(light.path("payload")).isEqualTo(MAPPER.readTree(MAPPER.writeValueAsString(payloads.light())));
        assertThat(light.path("payload").path("delta_metrics").has("delta_comeback")).isFalse();
        assertThat(light.has("reference")).as("1번 서버 요청 밖의 값은 저장하지 않는다").isFalse();

        JsonNode defense = MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_heavy_defense.json")));
        assertThat(defense.path("chapter_id").asString()).isEqualTo("defense");
        assertThat(defense.path("payload").toString())
                .isEqualTo("{\"chapter_id\":\"defense\",\"data\":{\"delta_vs_apm\":0.31,\"delta_cheese_index\":-2.1}}");
    }

    @Test
    void 연속_3회_실패하면_멈춘다() throws IOException {
        targets("a01", "a02", "a03", "a04");
        when(builder.analyze(anyString())).thenReturn(new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED));

        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_STOPPED);

        assertThat(usersCsv()).extracting(r -> r.get(2) + " " + r.get(3)).containsExactly(
                "error TETR.IO 수집 실패(FAILED)", "error TETR.IO 수집 실패(FAILED)", "error TETR.IO 수집 실패(FAILED)");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60));
        verify(builder, never()).analyze("a04");
    }

    @Test
    void 요청_제한_429는_rate_limited로_적고_다음까지_5분_기다린다() throws IOException {
        targets("r01", "r02", "ok1", "ok2");
        when(builder.analyze(anyString())).thenReturn(new AnalysisOutcome.CollectionFailed(CollectionStatus.RATE_LIMITED));
        when(builder.analyze("ok1")).thenReturn(new AnalysisOutcome.UserNotFound("ok1"));
        when(builder.analyze("ok2")).thenReturn(new AnalysisOutcome.UserNotFound("ok2"));

        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_DONE);

        assertThat(usersCsv()).extracting(r -> r.get(2) + " " + r.get(3)).containsExactly(
                "rate_limited 429", "rate_limited 429", "not_found 404", "not_found 404");
        // 429 뒤에는 5분, 정상으로 돌아오면 다시 1분
        assertThat(sleeps).containsExactly(Duration.ofSeconds(300), Duration.ofSeconds(300), Duration.ofSeconds(60));
    }

    @Test
    void 성공하면_연속_실패_수가_다시_0이_된다() throws IOException {
        targets("f01", "ok1", "f02", "f03");
        doThrow(new IllegalStateException("boom")).when(builder).analyze(anyString());
        doReturn(new AnalysisOutcome.UserNotFound("ok1")).when(builder).analyze("ok1");

        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_DONE);

        assertThat(usersCsv()).extracting(r -> r.get(2) + " " + r.get(3)).containsExactly(
                "error IllegalStateException", "not_found 404", "error IllegalStateException", "error IllegalStateException");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    @Test
    void 다시_실행하면_처리한_유저는_건너뛰고_이어서_한다() throws IOException {
        targets("icly", "sung1ow");
        when(builder.analyze(anyString())).thenReturn(new AnalysisOutcome.UserNotFound("?"));
        collector().run();

        targets("icly", "sung1ow", "newbie");
        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_DONE);

        verify(builder, times(1)).analyze("icly");
        verify(builder, times(1)).analyze("sung1ow");
        verify(builder, times(1)).analyze("newbie");
        assertThat(usersCsv()).extracting(r -> r.get(0)).containsExactly("u0001", "u0002", "u0003");
    }

    @Test
    void 대소문자와_공백만_다른_이름은_한_번만_소문자로_수집한다() throws IOException {
        targets("SunG1ow", " sung1ow ", "SUNG1OW");
        when(builder.analyze(anyString())).thenReturn(new AnalysisOutcome.UserNotFound("?"));

        collector().run();

        verify(builder, times(1)).analyze("sung1ow");
        assertThat(usersCsv()).hasSize(1);
    }

    @Test
    void 이미_다른_수집기가_돌고_있으면_바로_끝낸다() throws IOException {
        targets("icly");
        try (CollectorLock held = CollectorLock.tryAcquire(dir.resolve("collector.lock"))) {
            assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_LOCKED);
        }
        verify(builder, never()).analyze(any());
    }

    @Test
    void 명단이_없으면_설정_오류로_끝낸다() throws IOException {
        assertThat(collector().run()).isEqualTo(TrainingDataCollector.EXIT_CONFIG);
    }

    static final class FakeClock extends Clock {
        private Instant now;

        FakeClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
