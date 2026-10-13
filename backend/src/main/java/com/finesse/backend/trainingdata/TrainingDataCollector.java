package com.finesse.backend.trainingdata;

import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.service.TrainingPayloadBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 학습 데이터 수집기 본체 (학습 데이터 수집 서버 작업지시서 3·4절).
 * targets.csv를 읽어 유저 1명씩: TETR.IO 수집 1회 → 계산 1회 → LLM 요청(light 1 + heavy 7)을 만들어 JSON 8개로 저장 →
 * users.csv에 결과 한 행 → 직전 수집이 끝난 뒤 1분 대기. LLM은 부르지 않는다.
 * 로그에는 닉네임을 남기지 않고 user_key만 쓴다(4.5절).
 */
public class TrainingDataCollector {

    public static final int EXIT_DONE = 0;
    public static final int EXIT_CONFIG = 1;
    public static final int EXIT_STOPPED = 2;
    public static final int EXIT_LOCKED = 3;

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(TrainingDataCollector.class);

    /** 대기용 — 테스트에서 바꿔 끼운다 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** rank·rd 열이 생기기 전에 수집한 유저의 값을 채울 때 — 리그 요약 1회 조회(소문자 닉네임 → rank·rd) */
    @FunctionalInterface
    public interface RankLookup {
        UsersLog.RankRd lookup(String normalized);
    }

    /** rank·rd를 채우다가 이만큼 모이면 중간 저장 — 도중에 멈춰도 채운 만큼은 남는다 */
    static final int RANK_FILL_BATCH = 50;

    private final TrainingPayloadBuilder builder;
    private final JsonMapper mapper;
    private final TrainingCollectorProperties props;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Path workDir;
    private final RankLookup rankLookup;

    public TrainingDataCollector(TrainingPayloadBuilder builder, JsonMapper mapper, TrainingCollectorProperties props,
                                 Clock clock, Sleeper sleeper, Path workDir) {
        this(builder, mapper, props, clock, sleeper, workDir, null);
    }

    public TrainingDataCollector(TrainingPayloadBuilder builder, JsonMapper mapper, TrainingCollectorProperties props,
                                 Clock clock, Sleeper sleeper, Path workDir, RankLookup rankLookup) {
        this.builder = builder;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        this.workDir = workDir;
        this.rankLookup = rankLookup;
    }

    public int run() throws IOException {
        Path dataDir = props.dataDir();
        Path collected = dataDir.resolve("collected");
        Files.createDirectories(collected);

        try (CollectorLock lock = CollectorLock.tryAcquire(dataDir.resolve("collector.lock"))) {
            if (lock == null) {
                log.error("다른 수집기가 이미 실행 중입니다({}) — 이 실행은 종료합니다", dataDir.resolve("collector.lock"));
                return EXIT_LOCKED;
            }
            String calcCommit;
            List<String> targets;
            try {
                calcCommit = CalcCommit.resolve(props.calcCommit(), props.allowDirty(), workDir);
                targets = readTargets(props.targetsFile());
            } catch (IllegalStateException e) {
                log.error("수집을 시작할 수 없습니다: {}", e.getMessage());
                return EXIT_CONFIG;
            }
            Map<String, String> keys = UserKeyRegistry.load(dataDir.resolve("user_map.csv")).assignAll(targets);
            UsersLog users = UsersLog.load(dataDir.resolve("users.csv"));
            long fresh = keys.values().stream().filter(k -> !users.isDone(k)).count();
            long retry = keys.values().stream().filter(users::awaitingRetry).count();
            log.info("수집 시작 — calc_commit={}, 대상 {}명, 이미 처리 {}명, 남은 {}명(재시도 대기 {}명), 저장 위치 {}",
                    calcCommit, targets.size(), targets.size() - fresh, fresh, retry, dataDir);
            fillMissingRank(keys, users);
            return loop(targets, keys, users, collected, calcCommit);
        }
    }

    /**
     * rank·rd 열(10/13)이 생기기 전에 수집한 ok·insufficient 유저를 리그 요약 1회씩 조회해 채운다.
     * 수집 시점이 아니라 채우는 시점 값이다. 실패하면 멈추고 다음 시작 때 이어서 채운다.
     */
    private void fillMissingRank(Map<String, String> keys, UsersLog users) throws IOException {
        if (rankLookup == null) {
            return;
        }
        List<String> missing = users.missingRank();
        if (missing.isEmpty()) {
            return;
        }
        Map<String, String> nameByKey = new java.util.HashMap<>();
        keys.forEach((name, key) -> nameByKey.put(key, name));
        log.info("rank·rd가 빈 {}명을 리그 요약으로 채웁니다(유저당 TETR.IO 1회, 지금 시점 값)", missing.size());
        Map<String, UsersLog.RankRd> filled = new java.util.LinkedHashMap<>();
        int done = 0;
        for (String key : missing) {
            String name = nameByKey.get(key);
            if (name == null) {
                continue; // 지금 명단에 없는 유저 — 닉네임을 모른다
            }
            try {
                filled.put(key, rankLookup.lookup(TrainingPayloadBuilder.normalize(name)));
            } catch (com.finesse.backend.calc.exception.TetrIoUserNotFoundException e) {
                continue; // 그사이 없어진 유저 — 빈 칸으로 둔다
            } catch (RuntimeException e) {
                log.warn("rank·rd 채우기를 멈춥니다({}) — 채운 {}명은 저장하고, 나머지는 다음 시작 때 이어서", e.getClass().getSimpleName(),
                        done + filled.size());
                break;
            }
            if (filled.size() >= RANK_FILL_BATCH) {
                users.fillRank(filled);
                done += filled.size();
                filled.clear();
            }
        }
        users.fillRank(filled);
        done += filled.size();
        log.info("rank·rd 채우기 끝 — {}명", done);
    }

    /** 유저 사이 대기와 연속 실패 상태 — 처음 도는 명단과 재시도 명단이 이어서 쓴다 */
    private static final class Pace {
        Instant lastEnd;
        Duration wait;
        int failStreak;
        /** 연속 실패로 쉬었다가 다시 시작한 직후 — 이때 실패하면 3번을 채우지 않고 바로 다시 쉰다 */
        boolean probing;
        /** 다음 유저 전에 쉬어야 한다 — 남은 유저가 없으면 쉬지 않고 끝낸다 */
        boolean pauseNext;
    }

    private int loop(List<String> targets, Map<String, String> keys, UsersLog users, Path collected, String calcCommit)
            throws IOException {
        Map<CollectStatus, Integer> counts = new EnumMap<>(CollectStatus.class);
        Pace pace = new Pace();
        pace.wait = Duration.ofSeconds(props.intervalSeconds());

        // 1) 아직 시도하지 않은 유저
        for (String name : targets) {
            String key = keys.get(name);
            if (!users.isDone(key) && !collectAndPace(name, key, users, counts, collected, calcCommit, pace)) {
                logSummary(counts);
                return EXIT_STOPPED;
            }
        }
        // 2) error·rate_limited로 끝난 유저를 한 번 더 — 일시적인 장애로 빠진 유저를 살린다. 두 번째 결과로 확정
        boolean announced = false;
        for (String name : targets) {
            String key = keys.get(name);
            if (!users.awaitingRetry(key)) {
                continue;
            }
            if (!announced) {
                log.info("명단을 한 바퀴 돌았습니다 — error·rate_limited로 끝난 {}명을 한 번 더 시도합니다",
                        keys.values().stream().filter(users::awaitingRetry).count());
                announced = true;
            }
            if (!collectAndPace(name, key, users, counts, collected, calcCommit, pace)) {
                logSummary(counts);
                return EXIT_STOPPED;
            }
        }
        log.info("수집 완료 — 명단 끝까지 처리했습니다");
        logSummary(counts);
        return EXIT_DONE;
    }

    /** 유저 1명 수집 + 대기·연속 실패 처리. false면 중단 */
    private boolean collectAndPace(String name, String key, UsersLog users, Map<CollectStatus, Integer> counts,
                                   Path collected, String calcCommit, Pace pace) throws IOException {
        String normalized;
        try {
            normalized = TrainingPayloadBuilder.normalize(name);
        } catch (IllegalArgumentException e) {
            // 형식이 틀린 이름은 TETR.IO를 부르지 않는다 — 존재할 수 없는 유저라 not_found
            record(users, counts, key, new Result(CollectStatus.NOT_FOUND, "닉네임 형식 오류", null), Duration.ZERO);
            return true;
        }
        if (pace.pauseNext) {
            Duration pause = Duration.ofSeconds(props.failurePauseSeconds());
            log.warn("{} — {} 쉬었다가 이어서 수집합니다(실패한 유저는 명단 끝에서 한 번 더 시도)",
                    pace.probing ? "쉬고 나서 다시 시도한 유저도 실패(연속 " + pace.failStreak + "회)"
                            : "연속 " + pace.failStreak + "회 실패",
                    pause.toSeconds() % 60 == 0 ? pause.toMinutes() + "분" : pause.toSeconds() + "초");
            if (!sleepFor(pause)) {
                log.warn("대기 중 중단 요청을 받아 종료합니다");
                return false;
            }
            pace.pauseNext = false;
            pace.probing = true;
        } else if (pace.lastEnd != null && !sleepUntil(pace.lastEnd.plus(pace.wait))) {
            log.warn("대기 중 중단 요청을 받아 종료합니다");
            return false;
        }
        Instant started = clock.instant();
        Result result = collectOne(key, normalized, collected, calcCommit);
        pace.lastEnd = clock.instant();
        Duration took = Duration.between(started, pace.lastEnd);
        record(users, counts, key, result, took);
        if (took.toSeconds() >= props.slowUserWarnSeconds()) {
            log.warn("{} 수집이 {}초 걸렸습니다(기준 {}초) — 바로 위 TETR.IO 경고 로그에서 원인을 확인하세요",
                    key, took.toMillis() / 1000.0, props.slowUserWarnSeconds());
        }

        // 429면 대기를 늘린다(1분 → 5분, 4.6절). 성공하면 다시 1분
        pace.wait = Duration.ofSeconds(result.status() == CollectStatus.RATE_LIMITED
                ? props.rateLimitedIntervalSeconds() : props.intervalSeconds());
        if (!result.status().isFailure()) {
            pace.failStreak = 0;
            pace.probing = false;
            return true;
        }
        pace.failStreak++;
        if (pace.probing || pace.failStreak >= props.maxConsecutiveFailures()) {
            if (props.failurePauseSeconds() <= 0) {
                log.error("연속 {}회 실패로 수집을 중단합니다 — 원인을 확인한 뒤 다시 실행하면 {} 다음 유저부터 이어서 수집합니다",
                        pace.failStreak, key);
                return false;
            }
            pace.pauseNext = true;
        }
        return true;
    }

    /** 유저 1명: 수집 1회 → 계산 1회 → payload 8개 (작업지시서 3.1절) */
    Result collectOne(String key, String normalized, Path collected, String calcCommit) {
        String collectedAt = now();
        try {
            AnalysisOutcome outcome = builder.analyze(normalized);
            return switch (outcome) {
                case AnalysisOutcome.Analyzed analyzed -> {
                    TrainingPayloadBuilder.Payloads payloads = builder.payloads(normalized, analyzed);
                    writePayloads(key, collectedAt, calcCommit, payloads, collected);
                    yield new Result(CollectStatus.OK, null, payloads.matchCount(), collectedAt, rankRd(analyzed.summary()));
                }
                case AnalysisOutcome.ColdStartBypass cold ->
                        new Result(CollectStatus.INSUFFICIENT, insufficientReason(cold), null, collectedAt, rankRd(cold.summary()));
                case AnalysisOutcome.UserNotFound notFound ->
                        new Result(CollectStatus.NOT_FOUND, "404", null, collectedAt);
                // 429는 calc가 RATE_LIMITED로 구분해 준다(collect 브랜치), 5xx·타임아웃 등은 FAILED·INSUFFICIENT
                case AnalysisOutcome.CollectionFailed failed -> failed.status() == CollectionStatus.RATE_LIMITED
                        ? new Result(CollectStatus.RATE_LIMITED, "429", null, collectedAt)
                        : new Result(CollectStatus.ERROR, "TETR.IO 수집 실패(" + failed.status() + ")", null, collectedAt);
            };
        } catch (IOException | RuntimeException e) {
            // 예외 메시지에는 요청 URL(닉네임)이 들어갈 수 있어 클래스 이름만 남긴다
            log.warn("{} 수집 중 예외: {}", key, e.getClass().getName());
            return new Result(CollectStatus.ERROR, e.getClass().getSimpleName(), null, collectedAt);
        }
    }

    /** 표본 부족 3단계 (v1.2 2절) — 어느 단계든 학습 데이터 행은 생기지 않는다 */
    static String insufficientReason(AnalysisOutcome.ColdStartBypass cold) {
        int n = cold.availableMatches();
        return switch (cold.reason()) {
            case FEW_GAMES_TOTAL -> "1단계 누적 " + n + "판";
            case FEW_GAMES_IN_YEAR -> "2단계 1년내 " + n + "판";
            case TOO_MANY_INVALID -> n == 0 ? "3단계 정제후 0판(전부 제외)" : "3단계 정제후 " + n + "판";
        };
    }

    /** JSON 8개 — 쓰다 죽어도 반쯤 쓰인 파일이 남지 않게 임시 파일 → fsync → rename (3.4절) */
    private void writePayloads(String key, String collectedAt, String calcCommit,
                               TrainingPayloadBuilder.Payloads payloads, Path collected) throws IOException {
        ObjectNode light = meta(key, collectedAt, calcCommit, payloads.matchCount(), "light");
        light.set("payload", mapper.valueToTree(payloads.light()));
        write(collected.resolve(key + "_light.json"), light);

        for (Map.Entry<String, LlmHeavyChapterRequest> e : payloads.heavy().entrySet()) {
            ObjectNode heavy = meta(key, collectedAt, calcCommit, payloads.matchCount(), "heavy");
            heavy.put("chapter_id", e.getKey());
            heavy.set("payload", mapper.valueToTree(e.getValue()));
            write(collected.resolve(key + "_heavy_" + e.getKey() + ".json"), heavy);
        }
    }

    private ObjectNode meta(String key, String collectedAt, String calcCommit, int matchCount, String view) {
        ObjectNode node = mapper.createObjectNode();
        node.put("user_key", key);
        node.put("collected_at", collectedAt);
        node.put("calc_commit", calcCommit);
        node.put("match_count", matchCount);
        node.put("view", view);
        return node;
    }

    private void write(Path file, ObjectNode node) throws IOException {
        DurableFiles.writeAtomically(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    private void record(UsersLog users, Map<CollectStatus, Integer> counts, String key, Result result, Duration took)
            throws IOException {
        String collectedAt = result.collectedAt() != null ? result.collectedAt() : now();
        users.append(key, collectedAt, result.status(), result.failReason(), result.matchCount(), result.rankRd());
        counts.merge(result.status(), 1, Integer::sum);
        log.info("{} {}{}{}{} ({}초)", key, result.status().csv(),
                result.failReason() == null ? "" : " — " + result.failReason(),
                result.matchCount() == null ? "" : " — " + result.matchCount() + "판",
                result.rankRd() == null ? "" : " · " + UsersLog.rankCell(result.rankRd().rank())
                        + " RD " + UsersLog.rdCell(result.rankRd().rd()),
                took.toMillis() / 1000.0);
    }

    private void logSummary(Map<CollectStatus, Integer> counts) {
        log.info("이번 실행 결과 — {}", counts.isEmpty() ? "처리한 유저 없음" : counts);
    }

    private boolean sleepUntil(Instant until) {
        return sleepFor(Duration.between(clock.instant(), until));
    }

    private boolean sleepFor(Duration left) {
        if (left.isNegative() || left.isZero()) {
            return true;
        }
        try {
            sleeper.sleep(left);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String now() {
        return OffsetDateTime.ofInstant(clock.instant(), KST).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * targets.csv — username 한 열(머리글 username). 앞뒤 공백·대소문자를 정리해 같은 이름은 한 번만 수집한다
     * (TETR.IO는 대문자가 섞이면 없는 유저로 본다 — 4.3절).
     */
    static List<String> readTargets(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            throw new IllegalStateException("수집 대상 명단이 없습니다: " + file);
        }
        List<List<String>> rows = CsvFiles.read(file);
        if (rows.isEmpty()) {
            throw new IllegalStateException("수집 대상 명단이 비어 있습니다: " + file);
        }
        int col = -1;
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase("username")) {
                col = i;
            }
        }
        if (col < 0) {
            throw new IllegalStateException("명단 첫 줄에 username 머리글이 없습니다: " + file);
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() > col) {
                String name = row.get(col).trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    record Result(CollectStatus status, String failReason, Integer matchCount, String collectedAt, UsersLog.RankRd rankRd) {
        Result(CollectStatus status, String failReason, Integer matchCount, String collectedAt) {
            this(status, failReason, matchCount, collectedAt, null);
        }

        Result(CollectStatus status, String failReason, Integer matchCount) {
            this(status, failReason, matchCount, null, null);
        }
    }

    /** 리그 요약의 rank·rd (10/13) — 요약이 없으면 null */
    static UsersLog.RankRd rankRd(UserSummary summary) {
        return summary == null ? null : new UsersLog.RankRd(summary.rank(), summary.rd());
    }
}
