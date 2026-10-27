package com.finesse.backend.trainingdata;

import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.service.TrainingPayloadBuilder;
import com.finesse.backend.service.TrainingPayloadBuilder.LightItem;
import com.finesse.backend.trainingdata.export.TrainingCsvExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.finesse.backend.trainingdata.TrainingDataCollectorTest.MAPPER;
import static com.finesse.backend.trainingdata.TrainingDataCollectorTest.samplePayloads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수집기 LLM 코멘트(10/27) — 라이트 개별·기본 + 헤비 챕터 7개를 받아 JSON·CSV에 함께 저장한다.
 */
class TrainingLlmCommentTest {

    private static final Pattern STAT = Pattern.compile("\"stat\"\\s*:\\s*\"([a-z_]+)\"");

    @TempDir
    Path dir;

    private static CollectorLlmProperties props(String... servers) {
        return new CollectorLlmProperties(List.of(servers), 10, 2, 1,
                "/v1/light/sentence", "/v1/light/summary", "/v1/comment/heavy-chapter");
    }

    /** 보낸 요청 기록 + 정해 둔 응답을 차례로 돌려주는 가짜 LLM — 응답이 모자라면 정상 문장 */
    static final class FakeLlm implements TrainingLlmClient.Transport {
        final List<String> urls = new ArrayList<>();
        final List<Object> scripted = new ArrayList<>();   // Response 또는 IOException

        @Override
        public TrainingLlmClient.Response post(String url, byte[] body, java.time.Duration timeout) throws IOException {
            urls.add(url);
            if (!scripted.isEmpty()) {
                Object next = scripted.removeFirst();
                if (next instanceof IOException e) {
                    throw e;
                }
                return (TrainingLlmClient.Response) next;
            }
            String b = new String(body, StandardCharsets.UTF_8);
            if (url.endsWith("/sentence")) {
                Matcher m = STAT.matcher(b);
                String stat = m.find() ? m.group(1) : "?";
                return ok("{\"stat\":\"" + stat + "\",\"sentence\":\"문장 " + stat + "\",\"fallback\":false,\"version\":\"v-test\"}");
            }
            if (url.endsWith("/summary")) {
                return ok("{\"light_summary\":\"요약\",\"fallback\":false,\"version\":\"v-test\"}");
            }
            return ok("{\"chapter_id\":\"x\",\"footnote\":\"각주\",\"fallback\":false,\"version\":\"h-test\"}");
        }
    }

    static TrainingLlmClient.Response ok(String body) {
        return new TrainingLlmClient.Response(200, body);
    }

    private static final LightItem APP = new LightItem("delta_app", 0.092, "높음");

    @Test
    void 대체_문장이면_한_번_더_요청하고_두_번째_결과를_남긴다() {
        FakeLlm llm = new FakeLlm();
        llm.scripted.add(ok("{\"stat\":\"delta_app\",\"sentence\":\"대체\",\"fallback\":true,\"version\":\"v\"}"));
        TrainingLlmClient.Reply r = new TrainingLlmClient(props("http://a"), MAPPER, llm).sentence(APP);

        assertThat(r.text()).isEqualTo("문장 delta_app");
        assertThat(r.fallback()).isFalse();
        assertThat(r.attempts()).isEqualTo(2);
    }

    @Test
    void 다시_요청해도_대체_문장이면_그대로_남기고_fallback_true() {
        FakeLlm llm = new FakeLlm();
        llm.scripted.add(ok("{\"stat\":\"delta_app\",\"sentence\":\"대체1\",\"fallback\":true}"));
        llm.scripted.add(ok("{\"stat\":\"delta_app\",\"sentence\":\"대체2\",\"fallback\":true}"));
        TrainingLlmClient.Reply r = new TrainingLlmClient(props("http://a"), MAPPER, llm).sentence(APP);

        assertThat(r.text()).isEqualTo("대체2");
        assertThat(r.fallback()).isTrue();
        assertThat(r.attempts()).isEqualTo(2);
    }

    @Test
    void 연결_실패나_5xx면_다른_서버로_보낸다() {
        FakeLlm llm = new FakeLlm();
        llm.scripted.add(new java.net.ConnectException("refused"));
        TrainingLlmClient.Reply r = new TrainingLlmClient(props("http://a", "http://b"), MAPPER, llm).sentence(APP);
        assertThat(r.text()).isEqualTo("문장 delta_app");
        assertThat(llm.urls).containsExactly("http://a/v1/light/sentence", "http://b/v1/light/sentence");

        FakeLlm llm2 = new FakeLlm();
        llm2.scripted.add(new TrainingLlmClient.Response(503, "{}"));
        llm2.scripted.add(new TrainingLlmClient.Response(503, "{}"));
        TrainingLlmClient.Reply r2 = new TrainingLlmClient(props("http://a", "http://b"), MAPPER, llm2).summary(List.of(APP));
        assertThat(r2.ok()).isFalse();
        assertThat(r2.error()).isEqualTo("HTTP 503");
    }

    @Test
    void 요청_문제_4xx는_다시_보내지_않는다() {
        FakeLlm llm = new FakeLlm();
        llm.scripted.add(new TrainingLlmClient.Response(422, "{\"detail\":\"x\"}"));
        TrainingLlmClient.Reply r = new TrainingLlmClient(props("http://a", "http://b"), MAPPER, llm).sentence(APP);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("HTTP 422");
        assertThat(llm.urls).hasSize(1);
    }

    private static TrainingPayloadBuilder.Payloads payloadsWithPicks() {
        TrainingPayloadBuilder.Payloads base = samplePayloads();
        JudgedMetric app = new JudgedMetric("delta_app", "attack", 0.092, -0.05, 0.05, HighlightZone.HIGH, 0.42, null);
        JudgedMetric cheese = new JudgedMetric("delta_cheese_index", "defense", -2.1, -1.0, 1.0, HighlightZone.LOW, -0.55, null);
        HighlightJudgement j = new HighlightJudgement("ref-2026-10-15-g2", "t5", List.of(app, cheese), List.of(app, cheese));
        return new TrainingPayloadBuilder.Payloads(base.light(), base.heavy(), base.matchCount(), j,
                List.of(new LightItem("delta_app", 0.02, "높음"), new LightItem("delta_cheese_index", -2.1, "낮음")));
    }

    private FakeLlm collectWithLlm() throws IOException {
        Files.writeString(dir.resolve("targets.csv"), CsvFiles.BOM + "username\nicly\n", StandardCharsets.UTF_8);
        TrainingPayloadBuilder builder = mock(TrainingPayloadBuilder.class);
        AnalysisOutcome.Analyzed analyzed = new AnalysisOutcome.Analyzed(null, null, null);
        when(builder.analyze(anyString())).thenReturn(analyzed);
        when(builder.payloads("icly", analyzed)).thenReturn(payloadsWithPicks());
        FakeLlm llm = new FakeLlm();
        new TrainingDataCollector(builder, MAPPER,
                new TrainingCollectorProperties(dir, dir.resolve("targets.csv"), 60, 300, 3, 600, 30, "abc1234", false),
                Clock.fixed(Instant.parse("2026-10-27T09:03:00Z"), ZoneOffset.UTC), d -> { }, dir, null,
                new TrainingLlmClient(props("http://a", "http://b"), MAPPER, llm)).run();
        return llm;
    }

    @Test
    void 선정_지표마다_개별_코멘트_기본_1회_헤비_7챕터를_받아_JSON에_저장한다() throws IOException {
        FakeLlm llm = collectWithLlm();

        assertThat(llm.urls.stream().filter(u -> u.endsWith("/sentence"))).hasSize(2);
        assertThat(llm.urls.stream().filter(u -> u.endsWith("/summary"))).hasSize(1);
        assertThat(llm.urls.stream().filter(u -> u.endsWith("/heavy-chapter"))).hasSize(7);

        JsonNode light = MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_light.json")));
        assertThat(light.path("highlight_judgement").path("range_group").asString()).isEqualTo("t5");
        assertThat(light.path("highlight_judgement").path("picks").path(0).path("zone").asString()).isEqualTo("high");
        JsonNode comment = light.path("light_comment");
        assertThat(comment.path("requested").asBoolean()).isTrue();
        assertThat(comment.path("items").path(1).path("stat").asString()).isEqualTo("delta_cheese_index");
        assertThat(comment.path("items").path(1).path("comment").path("sentence").asString()).isEqualTo("문장 delta_cheese_index");
        assertThat(comment.path("summary").path("light_summary").asString()).isEqualTo("요약");

        JsonNode heavy = MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_heavy_attack.json")));
        assertThat(heavy.path("comment").path("footnote").asString()).isEqualTo("각주");
        assertThat(heavy.path("comment").path("version").asString()).isEqualTo("h-test");
    }

    @Test
    void CSV에_선정_지표와_LLM_코멘트_열이_채워진다() throws IOException {
        collectWithLlm();
        new TrainingCsvExporter().export(dir, dir.resolve("export"));

        List<List<String>> light = CsvFiles.read(dir.resolve("export/light.csv"));
        Map<String, String> l = row(light.get(0), light.get(1));
        assertThat(l).containsEntry("pick1_stat", "delta_app").containsEntry("pick2_stat", "delta_cheese_index")
                .containsEntry("pick3_stat", "").containsEntry("pick1_sentence", "")  // 사람이 쓰는 칸은 비움
                .containsEntry("ranges_version", "ref-2026-10-15-g2").containsEntry("range_group", "t5")
                .containsEntry("pick1_value", "0.02").containsEntry("pick1_zone", "높음")
                .containsEntry("llm_pick1_sentence", "문장 delta_app").containsEntry("llm_pick1_fallback", "false")
                .containsEntry("llm_light_summary", "요약").containsEntry("llm_version", "v-test");

        List<List<String>> heavy = CsvFiles.read(dir.resolve("export/heavy.csv"));
        Map<String, String> h = row(heavy.get(0), heavy.get(1));
        assertThat(h).containsEntry("footnote", "").containsEntry("llm_footnote", "각주")
                .containsEntry("llm_footnote_fallback", "false").containsEntry("llm_version", "h-test");
    }

    @Test
    void LLM_서버가_없으면_예전처럼_부르지_않고_requested_false() throws IOException {
        Files.writeString(dir.resolve("targets.csv"), CsvFiles.BOM + "username\nicly\n", StandardCharsets.UTF_8);
        TrainingPayloadBuilder builder = mock(TrainingPayloadBuilder.class);
        AnalysisOutcome.Analyzed analyzed = new AnalysisOutcome.Analyzed(null, null, null);
        when(builder.analyze(anyString())).thenReturn(analyzed);
        when(builder.payloads("icly", analyzed)).thenReturn(payloadsWithPicks());
        new TrainingDataCollector(builder, MAPPER,
                new TrainingCollectorProperties(dir, dir.resolve("targets.csv"), 60, 300, 3, 600, 30, "abc1234", false),
                Clock.fixed(Instant.parse("2026-10-27T09:03:00Z"), ZoneOffset.UTC), d -> { }, dir).run();

        JsonNode light = MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_light.json")));
        assertThat(light.path("light_comment").path("requested").asBoolean()).isFalse();
        assertThat(light.path("light_comment").path("items")).hasSize(2);
        assertThat(MAPPER.readTree(Files.readString(dir.resolve("collected/u0001_heavy_attack.json"))).has("comment")).isFalse();
        assertThat(new CollectorLlmProperties(List.of("", " "), 10, 2, 1, "a", "b", "c").enabled()).isFalse();
    }

    private static Map<String, String> row(List<String> header, List<String> values) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            m.put(header.get(i), values.get(i));
        }
        return m;
    }
}
