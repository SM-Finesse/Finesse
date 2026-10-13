package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * users.csv — 유저별 수집 결과이자 진행 기록 (작업지시서 4.4절: 별도 상태 파일을 두지 않는다).
 * 1건이 끝날 때마다 한 행을 append·fsync하고, 재시작하면 이미 있는 user_key를 건너뛴다.
 * error·rate_limited로 끝난 유저는 명단을 한 바퀴 돈 뒤 한 번 더 시도해 행을 하나 더 붙인다 — 같은 키가 두 번 있으면
 * 나중 행이 최종 결과다(CSV 변환기도 나중 행을 쓴다). 두 번째도 실패면 그대로 확정한다.
 * split 열은 PM이 채운다(학습 데이터 CSV 구조 지시서 3·4절) — 수집기는 비워 둔다.
 */
public final class UsersLog {

    public static final List<String> COLUMNS =
            List.of("user_key", "collected_at", "status", "fail_reason", "match_count", "split");
    static final String HEADER = String.join(",", COLUMNS) + "\n";

    /** 한 유저를 최대 몇 번 시도하는지 — 처음 1번 + 명단 끝에서 재시도 1번 */
    static final int MAX_ATTEMPTS = 2;

    private final Path file;
    private final Map<String, CollectStatus> latest = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();

    private UsersLog(Path file) {
        this.file = file;
    }

    public static UsersLog load(Path file) throws IOException {
        UsersLog log = new UsersLog(file);
        DurableFiles.dropPartialLastLine(file); // 쓰다 끊긴 행은 버린다 — 그 유저는 다시 수집
        if (Files.exists(file)) {
            List<List<String>> rows = CsvFiles.read(file);
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                log.remember(row.get(0), row.size() > 2 ? CollectStatus.fromCsv(row.get(2)) : null);
            }
        }
        return log;
    }

    /** 한 번이라도 시도했는지 — 처음 도는 명단에서 건너뛴다 */
    public boolean isDone(String userKey) {
        return latest.containsKey(userKey);
    }

    /** 처음 시도가 error·rate_limited였고 아직 다시 시도하지 않았는지 */
    public boolean awaitingRetry(String userKey) {
        CollectStatus status = latest.get(userKey);
        return status != null && status.isFailure() && attempts.getOrDefault(userKey, 0) < MAX_ATTEMPTS;
    }

    public int doneCount() {
        return latest.size();
    }

    public void append(String userKey, String collectedAt, CollectStatus status, String failReason, Integer matchCount)
            throws IOException {
        String line = CsvFiles.row(List.of(userKey, collectedAt, status.csv(), failReason == null ? "" : failReason,
                matchCount == null ? "" : matchCount.toString(), ""));
        DurableFiles.append(file, HEADER, line);
        remember(userKey, status);
    }

    private void remember(String userKey, CollectStatus status) {
        latest.put(userKey, status);
        attempts.merge(userKey, 1, Integer::sum);
    }
}
