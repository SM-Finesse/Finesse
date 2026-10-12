package com.finesse.backend.trainingdata.export;

import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.service.TrainingPayloadBuilder;
import com.finesse.backend.trainingdata.CsvFiles;
import com.finesse.backend.trainingdata.TrainingCollectorProperties;
import com.finesse.backend.trainingdata.TrainingDataCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.finesse.backend.trainingdata.TrainingDataCollectorTest.MAPPER;
import static com.finesse.backend.trainingdata.TrainingDataCollectorTest.samplePayloads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingCsvExporterTest {

    @TempDir
    Path dir;

    /** 실제 수집기로 JSON을 만든 뒤(icly ok, ghost not_found) 합친다 — 수집 → JSON → CSV 전 과정 */
    private void collect() throws IOException {
        Files.writeString(dir.resolve("targets.csv"), CsvFiles.BOM + "username\nicly\nghost\n", StandardCharsets.UTF_8);
        TrainingPayloadBuilder builder = mock(TrainingPayloadBuilder.class);
        AnalysisOutcome.Analyzed analyzed = new AnalysisOutcome.Analyzed(null, null, null);
        when(builder.analyze(anyString())).thenReturn(new AnalysisOutcome.UserNotFound("ghost"));
        when(builder.analyze("icly")).thenReturn(analyzed);
        when(builder.payloads("icly", analyzed)).thenReturn(samplePayloads());
        new TrainingDataCollector(builder, MAPPER,
                new TrainingCollectorProperties(dir, dir.resolve("targets.csv"), 60, 300, 3, "abc1234", false),
                Clock.fixed(Instant.parse("2026-10-10T09:03:00Z"), ZoneOffset.UTC), d -> { }, dir).run();
    }

    private Map<String, String> row(List<String> header, List<String> values) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            m.put(header.get(i), values.get(i));
        }
        return m;
    }

    @Test
    void light는_30열_heavy는_24열이고_머리글이_지시서와_같다() throws IOException {
        collect();
        new TrainingCsvExporter().export(dir, dir.resolve("export"));

        String lightText = Files.readString(dir.resolve("export/light.csv"), StandardCharsets.UTF_8);
        String heavyText = Files.readString(dir.resolve("export/heavy.csv"), StandardCharsets.UTF_8);
        assertThat(lightText).startsWith(CsvFiles.BOM + "sample_id,user_key,collected_at,calc_commit,split,win_rate,"
                + "tr_trend_json,recent_form_json,delta_opener,delta_plonk,delta_stride,delta_inf_ds,delta_app,"
                + "delta_weighted_app,delta_vs_apm,delta_cheese_index,strength_split,delta_comeback,session_vs_slope,"
                + "pick1_stat,pick1_sentence,pick2_stat,pick2_sentence,pick3_stat,pick3_sentence,light_summary,"
                + "author,reviewer,work_status,note\n");
        assertThat(heavyText).startsWith(CsvFiles.BOM + "sample_id,user_key,chapter_id,collected_at,calc_commit,split,"
                + "tr_trend_delta,delta_opener,delta_plonk,delta_stride,delta_inf_ds,delta_app,delta_weighted_app,"
                + "delta_vs_apm,delta_cheese_index,strength_split,comeback_rate,comeback_rate_against,session_vs_slope,"
                + "footnote,author,reviewer,work_status,note\n");
        List<List<String>> light = CsvFiles.parse(lightText);
        List<List<String>> heavy = CsvFiles.parse(heavyText);
        assertThat(light).hasSize(2).allSatisfy(r -> assertThat(r).hasSize(30));
        assertThat(heavy).hasSize(8).allSatisfy(r -> assertThat(r).hasSize(24)); // not_found 유저는 행이 없다
    }

    @Test
    void 요청에_없는_필드는_빈_칸_0은_값으로_쓴다() throws IOException {
        collect();
        new TrainingCsvExporter().export(dir, dir.resolve("export"));
        List<List<String>> light = CsvFiles.read(dir.resolve("export/light.csv"));
        Map<String, String> l = row(light.get(0), light.get(1));

        assertThat(l).containsEntry("sample_id", "L0001").containsEntry("user_key", "u0001")
                .containsEntry("collected_at", "2026-10-10T18:03:00+09:00").containsEntry("calc_commit", "abc1234")
                .containsEntry("win_rate", "0.62").containsEntry("tr_trend_json", "[21100.0,21250.0]")
                .containsEntry("recent_form_json", "[\"W\",\"L\"]")
                .containsEntry("delta_opener", "-0.05").containsEntry("delta_cheese_index", "-2.1")
                .containsEntry("strength_split", "-0.08")
                .containsEntry("delta_comeback", "")        // null 후보 — 서비스가 필드째 뺀다
                .containsEntry("session_vs_slope", "0.0")   // 실제 값 0
                .containsEntry("pick1_stat", "").containsEntry("light_summary", "").containsEntry("work_status", "todo");

        List<List<String>> heavy = CsvFiles.read(dir.resolve("export/heavy.csv"));
        Map<String, String> tr = row(heavy.get(0), heavy.get(1));
        Map<String, String> split = row(heavy.get(0), heavy.get(5));
        Map<String, String> comeback = row(heavy.get(0), heavy.get(6));
        assertThat(tr).containsEntry("sample_id", "H0001").containsEntry("chapter_id", "tr_trend")
                .containsEntry("tr_trend_delta", "128.4").containsEntry("delta_opener", "");
        assertThat(split).containsEntry("chapter_id", "strength_split").containsEntry("strength_split", "");
        // 표본 수(comeback_opportunities 등)는 열이 없어 CSV에 들어가지 않는다 — JSON에는 남아 있다
        assertThat(comeback).containsEntry("comeback_rate", "0.33").containsEntry("comeback_rate_against", "0.28")
                .containsEntry("tr_trend_delta", "");
    }

    @Test
    void null_값은_문자_null_지수_표기는_풀어서_쓴다() {
        JsonMapper m = JsonMapper.builder().build();
        var node = m.readTree("{\"a\":null,\"b\":0.0,\"c\":1.0E-4,\"d\":42}");

        assertThat(TrainingCsvExporter.cell(node, "a")).isEqualTo("null");
        assertThat(TrainingCsvExporter.cell(node, "b")).isEqualTo("0.0");
        assertThat(TrainingCsvExporter.cell(node, "c")).isEqualTo("0.0001");
        assertThat(TrainingCsvExporter.cell(node, "d")).isEqualTo("42");
        assertThat(TrainingCsvExporter.cell(node, "missing")).isEmpty();
    }

    @Test
    void split은_users_csv에서_가져오고_JSON이_빠진_유저는_건너뛴다() throws IOException {
        collect();
        Path users = dir.resolve("users.csv");
        Files.writeString(users, Files.readString(users, StandardCharsets.UTF_8)
                .replace("u0001,2026-10-10T18:03:00+09:00,ok,,183,", "u0001,2026-10-10T18:03:00+09:00,ok,,183,train"),
                StandardCharsets.UTF_8);

        new TrainingCsvExporter().export(dir, dir.resolve("export"));
        List<List<String>> light = CsvFiles.read(dir.resolve("export/light.csv"));
        assertThat(row(light.get(0), light.get(1))).containsEntry("split", "train");

        Files.delete(dir.resolve("collected/u0001_heavy_attack.json"));
        TrainingCsvExporter.Summary s = new TrainingCsvExporter().export(dir, dir.resolve("export"));
        assertThat(s.lightRows()).isZero();
        assertThat(s.problems()).hasSize(1);
    }
}
