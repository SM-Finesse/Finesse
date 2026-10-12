package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * users.csv — 유저별 수집 결과이자 진행 기록 (작업지시서 4.4절: 별도 상태 파일을 두지 않는다).
 * 1건이 끝날 때마다 한 행을 append·fsync하고, 재시작하면 이미 있는 user_key를 건너뛴다.
 * split 열은 PM이 채운다(학습 데이터 CSV 구조 지시서 3·4절) — 수집기는 비워 둔다.
 */
public final class UsersLog {

    public static final List<String> COLUMNS =
            List.of("user_key", "collected_at", "status", "fail_reason", "match_count", "split");
    static final String HEADER = String.join(",", COLUMNS) + "\n";

    private final Path file;
    private final Set<String> done = new LinkedHashSet<>();

    private UsersLog(Path file) {
        this.file = file;
    }

    public static UsersLog load(Path file) throws IOException {
        UsersLog log = new UsersLog(file);
        DurableFiles.dropPartialLastLine(file); // 쓰다 끊긴 행은 버린다 — 그 유저는 다시 수집
        if (Files.exists(file)) {
            List<List<String>> rows = CsvFiles.read(file);
            for (int i = 1; i < rows.size(); i++) {
                log.done.add(rows.get(i).get(0));
            }
        }
        return log;
    }

    public boolean isDone(String userKey) {
        return done.contains(userKey);
    }

    public int doneCount() {
        return done.size();
    }

    public void append(String userKey, String collectedAt, CollectStatus status, String failReason, Integer matchCount)
            throws IOException {
        String line = CsvFiles.row(List.of(userKey, collectedAt, status.csv(), failReason == null ? "" : failReason,
                matchCount == null ? "" : matchCount.toString(), ""));
        DurableFiles.append(file, HEADER, line);
        done.add(userKey);
    }
}
