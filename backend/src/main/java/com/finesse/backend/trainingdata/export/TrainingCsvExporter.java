package com.finesse.backend.trainingdata.export;

import com.finesse.backend.trainingdata.CsvFiles;
import com.finesse.backend.trainingdata.DurableFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집한 JSON → light.csv · heavy.csv (학습 데이터 수집 서버 작업지시서 3.5절 — 수집기와 따로 실행).
 * 열 구조는 「학습 데이터 CSV 구조 지시서」 5·6절이 정본: light 30열, heavy 24열.
 * 칸 값 규칙(5.2절): 요청에 필드가 없으면 빈 칸, null 값이면 문자 null, 숫자는 그대로(0.0도 값).
 * 사람이 채울 열은 비워 두고 work_status만 todo. split은 users.csv에서 가져온다(PM이 채운 뒤 다시 돌리면 된다).
 *
 * 실행: ./gradlew exportTrainingCsv --args="--data-dir=<수집 폴더> [--out-dir=<출력 폴더>]"
 */
public final class TrainingCsvExporter {

    public static final List<String> LIGHT_COLUMNS = List.of(
            "sample_id", "user_key", "collected_at", "calc_commit", "split",
            "win_rate", "tr_trend_json", "recent_form_json",
            "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
            "delta_app", "delta_weighted_app", "delta_vs_apm", "delta_cheese_index",
            "strength_split", "delta_comeback", "session_vs_slope",
            "pick1_stat", "pick1_sentence", "pick2_stat", "pick2_sentence", "pick3_stat", "pick3_sentence",
            "light_summary", "author", "reviewer", "work_status", "note");

    public static final List<String> HEAVY_COLUMNS = List.of(
            "sample_id", "user_key", "chapter_id", "collected_at", "calc_commit", "split",
            "tr_trend_delta", "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds",
            "delta_app", "delta_weighted_app", "delta_vs_apm", "delta_cheese_index",
            "strength_split", "comeback_rate", "comeback_rate_against", "session_vs_slope",
            "footnote", "author", "reviewer", "work_status", "note");

    /** heavy 챕터별로 채우는 지표 열 (CSV 구조 지시서 6.1절 B) — 이 밖의 data 필드는 CSV에 넣지 않는다 */
    static final Map<String, List<String>> CHAPTER_METRICS = chapterMetrics();

    private static final String TODO = "todo";

    private final JsonMapper mapper = JsonMapper.builder().build();

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(System.getProperty("user.home"), "finesse-collector");
        Path outDir = null;
        for (String arg : args) {
            if (arg.startsWith("--data-dir=")) {
                dataDir = Path.of(arg.substring("--data-dir=".length()));
            } else if (arg.startsWith("--out-dir=")) {
                outDir = Path.of(arg.substring("--out-dir=".length()));
            }
        }
        Summary s = new TrainingCsvExporter().export(dataDir, outDir != null ? outDir : dataDir.resolve("export"));
        System.out.println("light.csv " + s.lightRows() + "행 · heavy.csv " + s.heavyRows() + "행 → " + s.outDir());
        s.problems().forEach(p -> System.out.println("건너뜀: " + p));
    }

    public Summary export(Path dataDir, Path outDir) throws IOException {
        Map<String, Map<String, String>> users = readUsers(dataDir.resolve("users.csv"));
        Path collected = dataDir.resolve("collected");
        List<String> problems = new ArrayList<>();
        StringBuilder light = new StringBuilder(String.join(",", LIGHT_COLUMNS)).append('\n');
        StringBuilder heavy = new StringBuilder(String.join(",", HEAVY_COLUMNS)).append('\n');
        int lightRows = 0;
        int heavyRows = 0;

        for (Map.Entry<String, Map<String, String>> u : users.entrySet()) {
            String key = u.getKey();
            if (!"ok".equals(u.getValue().get("status"))) {
                continue;
            }
            String split = u.getValue().getOrDefault("split", "");
            List<JsonNode> files = new ArrayList<>();
            try {
                files.add(read(collected.resolve(key + "_light.json")));
                for (String chapter : CHAPTER_METRICS.keySet()) {
                    files.add(read(collected.resolve(key + "_heavy_" + chapter + ".json")));
                }
            } catch (IOException | RuntimeException e) {
                // 8개가 다 있어야 그 유저를 쓴다 — 하나라도 없거나 깨졌으면 통째로 건너뛴다
                problems.add(key + " (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            light.append(CsvFiles.row(lightRow(String.format("L%04d", ++lightRows), files.get(0), split)));
            int i = 1;
            for (String chapter : CHAPTER_METRICS.keySet()) {
                heavy.append(CsvFiles.row(heavyRow(String.format("H%04d", ++heavyRows), chapter, files.get(i++), split)));
            }
        }

        Files.createDirectories(outDir);
        DurableFiles.writeAtomically(outDir.resolve("light.csv"), (CsvFiles.BOM + light).getBytes(StandardCharsets.UTF_8));
        DurableFiles.writeAtomically(outDir.resolve("heavy.csv"), (CsvFiles.BOM + heavy).getBytes(StandardCharsets.UTF_8));
        return new Summary(lightRows, heavyRows, outDir, problems);
    }

    List<String> lightRow(String sampleId, JsonNode file, String split) {
        JsonNode payload = file.path("payload");
        JsonNode fixed = payload.path("fixed_metrics");
        JsonNode delta = payload.path("delta_metrics");
        JsonNode playstyle = delta.path("playstyle_relative");
        JsonNode attack = delta.path("attack");
        JsonNode defense = delta.path("defense");
        List<String> row = new ArrayList<>(List.of(
                sampleId, text(file, "user_key"), text(file, "collected_at"), text(file, "calc_commit"), split,
                cell(fixed, "win_rate"), json(fixed, "tr_trend"), json(file.path("reference"), "recent_form"),
                cell(playstyle, "delta_opener"), cell(playstyle, "delta_plonk"),
                cell(playstyle, "delta_stride"), cell(playstyle, "delta_inf_ds"),
                cell(attack, "delta_app"), cell(attack, "delta_weighted_app"),
                cell(defense, "delta_vs_apm"), cell(defense, "delta_cheese_index"),
                cell(delta, "strength_split"), cell(delta, "delta_comeback"), cell(delta, "session_vs_slope"),
                "", "", "", "", "", "", "", "", "", TODO, ""));
        check(row, LIGHT_COLUMNS);
        return row;
    }

    List<String> heavyRow(String sampleId, String chapter, JsonNode file, String split) {
        JsonNode data = file.path("payload").path("data");
        List<String> metrics = CHAPTER_METRICS.get(chapter);
        List<String> row = new ArrayList<>(List.of(sampleId, text(file, "user_key"), chapter,
                text(file, "collected_at"), text(file, "calc_commit"), split));
        for (String column : HEAVY_COLUMNS.subList(6, 19)) {
            row.add(metrics.contains(column) ? cell(data, column) : "");
        }
        row.addAll(List.of("", "", "", TODO, ""));
        check(row, HEAVY_COLUMNS);
        return row;
    }

    /** 필드가 없으면 빈 칸(요청에서 뺀 것), JSON null이면 문자 null, 숫자는 지수 표기 없이 */
    static String cell(JsonNode parent, String field) {
        if (parent == null || parent.isMissingNode() || !parent.has(field)) {
            return "";
        }
        JsonNode v = parent.get(field);
        if (v.isNull()) {
            return "null";
        }
        if (v.isIntegralNumber()) {
            return v.asString();
        }
        if (v.isNumber()) {
            return plain(v.asDouble());
        }
        return v.isString() ? v.asString() : v.toString();
    }

    /** 0.0001 같은 값이 1.0E-4로 찍히지 않게 — 값은 그대로, 표기만 바꾼다 */
    static String plain(double d) {
        String s = Double.toString(d);
        if (s.indexOf('E') < 0) {
            return s;
        }
        String p = new BigDecimal(s).stripTrailingZeros().toPlainString();
        return p.indexOf('.') < 0 ? p + ".0" : p;
    }

    private String json(JsonNode parent, String field) {
        if (parent == null || parent.isMissingNode() || !parent.has(field)) {
            return "";
        }
        JsonNode v = parent.get(field);
        return v.isNull() ? "null" : mapper.writeValueAsString(v);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString();
    }

    private JsonNode read(Path file) throws IOException {
        return mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** users.csv → user_key 순(u0001, u0002 …)으로 열 이름 → 값 */
    static Map<String, Map<String, String>> readUsers(Path file) throws IOException {
        List<List<String>> rows = CsvFiles.read(file);
        Map<String, Map<String, String>> users = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return users;
        }
        List<String> header = rows.get(0);
        rows.subList(1, rows.size()).stream()
                .sorted(Comparator.comparingInt(r -> number(r.get(0))))
                .forEach(r -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    for (int i = 0; i < header.size() && i < r.size(); i++) {
                        m.put(header.get(i), r.get(i));
                    }
                    users.put(r.get(0), m); // 같은 키가 두 번 있으면 나중 행(다시 수집한 결과)
                });
        return users;
    }

    private static int number(String key) {
        try {
            return Integer.parseInt(key.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void check(List<String> row, List<String> columns) {
        if (row.size() != columns.size()) {
            throw new IllegalStateException("열 수가 맞지 않습니다: " + row.size() + " ≠ " + columns.size());
        }
    }

    private static Map<String, List<String>> chapterMetrics() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("tr_trend", List.of("tr_trend_delta"));
        m.put("playstyle", List.of("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds"));
        m.put("attack", List.of("delta_app", "delta_weighted_app"));
        m.put("defense", List.of("delta_vs_apm", "delta_cheese_index"));
        m.put("strength_split", List.of("strength_split"));
        m.put("comeback_rate", List.of("comeback_rate", "comeback_rate_against"));
        m.put("session_vs_slope", List.of("session_vs_slope"));
        return m;
    }

    public record Summary(int lightRows, int heavyRows, Path outDir, List<String> problems) {
    }
}
