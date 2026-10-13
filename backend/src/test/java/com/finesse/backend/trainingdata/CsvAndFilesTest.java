package com.finesse.backend.trainingdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvAndFilesTest {

    @TempDir
    Path dir;

    @Test
    void 쉼표_따옴표가_든_값은_감싸고_다시_읽으면_같다() {
        List<String> cells = List.of("u0001", "상대보다 높고, \"강하다\"", "", "[\"W\",\"L\"]", "0.0");
        String line = CsvFiles.row(cells);

        assertThat(line).isEqualTo("u0001,\"상대보다 높고, \"\"강하다\"\"\",,\"[\"\"W\"\",\"\"L\"\"]\",0.0\n");
        assertThat(CsvFiles.parse(CsvFiles.BOM + "a,b,c,d,e\n" + line)).containsExactly(
                List.of("a", "b", "c", "d", "e"), cells);
    }

    @Test
    void 지시서_샘플_light_행을_그대로_읽는다() {
        String sample = "sample_id,tr_trend_json,recent_form_json,delta_comeback,session_vs_slope,note\n"
                + "L0003,\"[20300,20410]\",\"[\"\"W\"\",\"\"L\"\"]\",,1.6,경계 ① (delta_comeback 생략)\n"
                + "L0002,\"[1]\",\"[]\",null,0.0,\"경계 ③+② (플레이스타일 null, delta_comeback null)\"\n";

        List<List<String>> rows = CsvFiles.parse(sample);

        assertThat(rows.get(1)).containsExactly("L0003", "[20300,20410]", "[\"W\",\"L\"]", "", "1.6", "경계 ① (delta_comeback 생략)");
        assertThat(rows.get(2)).containsExactly("L0002", "[1]", "[]", "null", "0.0", "경계 ③+② (플레이스타일 null, delta_comeback null)");
    }

    @Test
    void append는_처음에만_BOM과_머리글을_쓴다() throws IOException {
        Path f = dir.resolve("users.csv");
        DurableFiles.append(f, "a,b\n", "1,2\n");
        DurableFiles.append(f, "a,b\n", "3,4\n");

        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo(CsvFiles.BOM + "a,b\n1,2\n3,4\n");
    }

    @Test
    void 쓰다_끊긴_마지막_줄은_버린다() throws IOException {
        Path f = dir.resolve("users.csv");
        Files.writeString(f, CsvFiles.BOM + "a,b\n1,2\n3,", StandardCharsets.UTF_8);

        assertThat(DurableFiles.dropPartialLastLine(f)).isTrue();
        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo(CsvFiles.BOM + "a,b\n1,2\n");
        assertThat(DurableFiles.dropPartialLastLine(f)).isFalse();
    }

    @Test
    void 원자적_쓰기는_임시_파일을_남기지_않는다() throws IOException {
        Path f = dir.resolve("u0001_light.json");
        DurableFiles.writeAtomically(f, "{}".getBytes(StandardCharsets.UTF_8));
        DurableFiles.writeAtomically(f, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(f)).isEqualTo("{\"a\":1}");
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(f);
        }
    }

    @Test
    void user_key는_명단_순서대로_발급하고_재시작해도_같다() throws IOException {
        Path f = dir.resolve("user_map.csv");
        Map<String, String> first = UserKeyRegistry.load(f).assignAll(List.of("icly", "sung1ow"));
        Map<String, String> second = UserKeyRegistry.load(f).assignAll(List.of("sung1ow", "newbie", "icly"));

        assertThat(first).containsEntry("icly", "u0001").containsEntry("sung1ow", "u0002");
        assertThat(second).containsEntry("sung1ow", "u0002").containsEntry("newbie", "u0003").containsEntry("icly", "u0001");
        assertThat(Files.readString(f, StandardCharsets.UTF_8))
                .isEqualTo(CsvFiles.BOM + "user_key,username\nu0001,icly\nu0002,sung1ow\nu0003,newbie\n");
    }

    @Test
    void users_csv에_있는_키는_처리한_것으로_본다() throws IOException {
        Path f = dir.resolve("users.csv");
        UsersLog log = UsersLog.load(f);
        log.append("u0001", "2026-10-10T18:03:00+09:00", CollectStatus.OK, null, 183,
                new UsersLog.RankRd("s+", 61.234));
        log.append("u0002", "2026-10-10T18:04:00+09:00", CollectStatus.INSUFFICIENT, "1단계 누적 7판", null,
                new UsersLog.RankRd("z", -1));
        log.append("u0003", "2026-10-10T18:05:00+09:00", CollectStatus.NOT_FOUND, "404", null);

        UsersLog reloaded = UsersLog.load(f);

        assertThat(reloaded.isDone("u0001")).isTrue();
        assertThat(reloaded.isDone("u0002")).isTrue();
        assertThat(reloaded.isDone("u0004")).isFalse();
        // rank는 대문자, rd는 소수 둘째 자리(10판 미만 -1), 리그 요약이 없으면 빈 칸
        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo(CsvFiles.BOM
                + "user_key,collected_at,status,fail_reason,match_count,rank,rd,split\n"
                + "u0001,2026-10-10T18:03:00+09:00,ok,,183,S+,61.23,\n"
                + "u0002,2026-10-10T18:04:00+09:00,insufficient,1단계 누적 7판,,Z,-1,\n"
                + "u0003,2026-10-10T18:05:00+09:00,not_found,404,,,,\n");
    }

    @Test
    void 예전_머리글의_users_csv는_rank_rd_열을_넣어_다시_쓰고_split은_그대로_둔다() throws IOException {
        Path f = dir.resolve("users.csv");
        Files.writeString(f, CsvFiles.BOM + "user_key,collected_at,status,fail_reason,match_count,split\n"
                + "u0001,2026-10-12T17:07:23+09:00,ok,,252,train\n"
                + "u0002,2026-10-12T17:08:27+09:00,error,X,,\n", StandardCharsets.UTF_8);

        UsersLog log = UsersLog.load(f);

        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo(CsvFiles.BOM
                + "user_key,collected_at,status,fail_reason,match_count,rank,rd,split\n"
                + "u0001,2026-10-12T17:07:23+09:00,ok,,252,,,train\n"
                + "u0002,2026-10-12T17:08:27+09:00,error,X,,,,\n");
        assertThat(log.missingRank()).containsExactly("u0001"); // error는 리그 요약이 없어 채울 대상이 아니다
    }

    @Test
    void 나중에_조회한_rank_rd는_그_유저의_최종_행에_채운다() throws IOException {
        Path f = dir.resolve("users.csv");
        UsersLog log = UsersLog.load(f);
        log.append("u0001", "2026-10-12T17:07:23+09:00", CollectStatus.ERROR, "X", null);
        log.append("u0002", "2026-10-12T17:08:27+09:00", CollectStatus.OK, null, 200);
        log.append("u0001", "2026-10-13T12:24:46+09:00", CollectStatus.OK, null, 98); // 재시도 성공

        log.fillRank(java.util.Map.of("u0001", new UsersLog.RankRd("z", 136.566), "u0002", new UsersLog.RankRd("a+", 60.5)));

        assertThat(Files.readString(f, StandardCharsets.UTF_8)).isEqualTo(CsvFiles.BOM
                + "user_key,collected_at,status,fail_reason,match_count,rank,rd,split\n"
                + "u0001,2026-10-12T17:07:23+09:00,error,X,,,,\n"
                + "u0002,2026-10-12T17:08:27+09:00,ok,,200,A+,60.50,\n"
                + "u0001,2026-10-13T12:24:46+09:00,ok,,98,Z,136.57,\n");
        assertThat(UsersLog.load(f).missingRank()).isEmpty();
    }

    @Test
    void 수집기는_하나만_뜬다() throws IOException {
        Path lockFile = dir.resolve("collector.lock");
        try (CollectorLock first = CollectorLock.tryAcquire(lockFile)) {
            assertThat(first).isNotNull();
            assertThat(CollectorLock.tryAcquire(lockFile)).isNull();
        }
        try (CollectorLock again = CollectorLock.tryAcquire(lockFile)) {
            assertThat(again).isNotNull();
        }
    }
}
