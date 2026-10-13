package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * users.csv — 유저별 수집 결과이자 진행 기록 (작업지시서 4.4절: 별도 상태 파일을 두지 않는다).
 * 1건이 끝날 때마다 한 행을 append·fsync하고, 재시작하면 이미 있는 user_key를 건너뛴다.
 * error·rate_limited로 끝난 유저는 명단을 한 바퀴 돈 뒤 한 번 더 시도해 행을 하나 더 붙인다 — 같은 키가 두 번 있으면
 * 나중 행이 최종 결과다(CSV 변환기도 나중 행을 쓴다). 두 번째도 실패면 그대로 확정한다.
 * rank·rd는 수집 시점 TETR.IO 리그 요약 값이다(10/13 추가) — rank는 대문자(X+ … D, 미배치·RD 100 초과는 Z),
 * rd는 Glicko-2 RD(10판 미만이면 -1, 쉬면 올라간다). 리그 요약을 못 받은 not_found·error·rate_limited는 빈 칸.
 * split 열은 PM이 채운다(학습 데이터 CSV 구조 지시서 3·4절) — 수집기는 비워 둔다.
 */
public final class UsersLog {

    public static final List<String> COLUMNS =
            List.of("user_key", "collected_at", "status", "fail_reason", "match_count", "rank", "rd", "split");
    static final String HEADER = String.join(",", COLUMNS) + "\n";
    private static final int RANK = COLUMNS.indexOf("rank");
    private static final int RD = COLUMNS.indexOf("rd");

    /** 한 유저를 최대 몇 번 시도하는지 — 처음 1번 + 명단 끝에서 재시도 1번 */
    static final int MAX_ATTEMPTS = 2;

    private final Path file;
    private final Map<String, CollectStatus> latest = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    /** 파일 내용(머리글 제외, COLUMNS 순서) — rank·rd를 나중에 채울 때 파일을 다시 쓴다 */
    private final List<List<String>> rows = new ArrayList<>();
    private final Map<String, Integer> latestRow = new HashMap<>();

    private UsersLog(Path file) {
        this.file = file;
    }

    public static UsersLog load(Path file) throws IOException {
        UsersLog log = new UsersLog(file);
        DurableFiles.dropPartialLastLine(file); // 쓰다 끊긴 행은 버린다 — 그 유저는 다시 수집
        if (!Files.exists(file)) {
            return log;
        }
        List<List<String>> raw = CsvFiles.read(file);
        if (raw.isEmpty()) {
            return log;
        }
        List<String> header = raw.get(0).stream().map(String::trim).toList();
        for (int i = 1; i < raw.size(); i++) {
            List<String> src = raw.get(i);
            List<String> row = new ArrayList<>(COLUMNS.size());
            for (String col : COLUMNS) {
                int at = header.indexOf(col);
                row.add(at >= 0 && at < src.size() ? src.get(at) : "");
            }
            log.rows.add(row);
            log.remember(row.get(0), CollectStatus.fromCsv(row.get(2)), log.rows.size() - 1);
        }
        if (!header.equals(COLUMNS)) {
            log.rewrite(); // 예전 머리글(rank·rd 없음) → 새 머리글. 없던 열은 빈 칸
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

    /** 리그 요약을 받았는데(ok·insufficient) rank가 비어 있는 유저 — rank·rd 열이 생기기 전에 수집한 유저 */
    public List<String> missingRank() {
        List<String> keys = new ArrayList<>();
        latestRow.forEach((key, at) -> {
            List<String> row = rows.get(at);
            CollectStatus s = latest.get(key);
            if ((s == CollectStatus.OK || s == CollectStatus.INSUFFICIENT) && row.get(RANK).isEmpty()) {
                keys.add(key);
            }
        });
        keys.sort(null);
        return keys;
    }

    /** 나중에 조회한 rank·rd를 각 유저의 최종 행에 채우고 파일을 한 번에 다시 쓴다 */
    public void fillRank(Map<String, RankRd> values) throws IOException {
        if (values.isEmpty()) {
            return;
        }
        values.forEach((key, v) -> {
            Integer at = latestRow.get(key);
            if (at != null) {
                rows.get(at).set(RANK, rankCell(v.rank()));
                rows.get(at).set(RD, rdCell(v.rd()));
            }
        });
        rewrite();
    }

    public int doneCount() {
        return latest.size();
    }

    public void append(String userKey, String collectedAt, CollectStatus status, String failReason, Integer matchCount)
            throws IOException {
        append(userKey, collectedAt, status, failReason, matchCount, null);
    }

    public void append(String userKey, String collectedAt, CollectStatus status, String failReason, Integer matchCount,
                       RankRd rankRd) throws IOException {
        List<String> row = List.of(userKey, collectedAt, status.csv(), failReason == null ? "" : failReason,
                matchCount == null ? "" : matchCount.toString(),
                rankRd == null ? "" : rankCell(rankRd.rank()), rankRd == null ? "" : rdCell(rankRd.rd()), "");
        DurableFiles.append(file, HEADER, CsvFiles.row(row));
        rows.add(new ArrayList<>(row));
        remember(userKey, status, rows.size() - 1);
    }

    /** 리그 요약의 rank·rd */
    public record RankRd(String rank, double rd) {
    }

    static String rankCell(String rank) {
        return rank == null ? "" : rank.toUpperCase(Locale.ROOT);
    }

    static String rdCell(double rd) {
        return rd < 0 ? "-1" : String.format(Locale.ROOT, "%.2f", rd);
    }

    private void remember(String userKey, CollectStatus status, int rowIndex) {
        latest.put(userKey, status);
        attempts.merge(userKey, 1, Integer::sum);
        latestRow.put(userKey, rowIndex);
    }

    private void rewrite() throws IOException {
        StringBuilder sb = new StringBuilder(CsvFiles.BOM).append(HEADER);
        for (List<String> row : rows) {
            sb.append(CsvFiles.row(row));
        }
        DurableFiles.writeAtomically(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
