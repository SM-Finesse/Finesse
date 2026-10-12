package com.finesse.backend.trainingdata;

import com.finesse.backend.calc.collector.CollectionStatus;
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

    private final TrainingPayloadBuilder builder;
    private final JsonMapper mapper;
    private final TrainingCollectorProperties props;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Path workDir;

    public TrainingDataCollector(TrainingPayloadBuilder builder, JsonMapper mapper, TrainingCollectorProperties props,
                                 Clock clock, Sleeper sleeper, Path workDir) {
        this.builder = builder;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        this.workDir = workDir;
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
            long remaining = keys.values().stream().filter(k -> !users.isDone(k)).count();
            log.info("수집 시작 — calc_commit={}, 대상 {}명, 이미 처리 {}명, 남은 {}명, 저장 위치 {}",
                    calcCommit, targets.size(), targets.size() - remaining, remaining, dataDir);
            return loop(targets, keys, users, collected, calcCommit);
        }
    }

    private int loop(List<String> targets, Map<String, String> keys, UsersLog users, Path collected, String calcCommit)
            throws IOException {
        Map<CollectStatus, Integer> counts = new EnumMap<>(CollectStatus.class);
        Instant lastEnd = null;
        Duration wait = Duration.ofSeconds(props.intervalSeconds());
        int consecutiveFailures = 0;
        for (String name : targets) {
            String key = keys.get(name);
            if (users.isDone(key)) {
                continue;
            }
            String normalized;
            try {
                normalized = TrainingPayloadBuilder.normalize(name);
            } catch (IllegalArgumentException e) {
                // 형식이 틀린 이름은 TETR.IO를 부르지 않는다 — 존재할 수 없는 유저라 not_found
                record(users, counts, key, new Result(CollectStatus.NOT_FOUND, "닉네임 형식 오류", null), Duration.ZERO);
                continue;
            }
            if (lastEnd != null && !sleepUntil(lastEnd.plus(wait))) {
                log.warn("대기 중 중단 요청을 받아 종료합니다");
                return EXIT_STOPPED;
            }
            Instant started = clock.instant();
            Result result = collectOne(key, normalized, collected, calcCommit);
            lastEnd = clock.instant();
            record(users, counts, key, result, Duration.between(started, lastEnd));

            // 429면 대기를 늘린다(1분 → 5분, 4.6절). 성공하면 다시 1분
            wait = Duration.ofSeconds(result.status() == CollectStatus.RATE_LIMITED
                    ? props.rateLimitedIntervalSeconds() : props.intervalSeconds());
            if (result.status().isFailure()) {
                consecutiveFailures++;
                if (consecutiveFailures >= props.maxConsecutiveFailures()) {
                    log.error("연속 {}회 실패로 수집을 중단합니다 — 원인을 확인한 뒤 다시 실행하면 {} 다음 유저부터 이어서 수집합니다",
                            consecutiveFailures, key);
                    logSummary(counts);
                    return EXIT_STOPPED;
                }
            } else {
                consecutiveFailures = 0;
            }
        }
        log.info("수집 완료 — 명단 끝까지 처리했습니다");
        logSummary(counts);
        return EXIT_DONE;
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
                    yield new Result(CollectStatus.OK, null, payloads.matchCount(), collectedAt);
                }
                case AnalysisOutcome.ColdStartBypass cold ->
                        new Result(CollectStatus.INSUFFICIENT, insufficientReason(cold), null, collectedAt);
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
        users.append(key, collectedAt, result.status(), result.failReason(), result.matchCount());
        counts.merge(result.status(), 1, Integer::sum);
        log.info("{} {}{}{} ({}초)", key, result.status().csv(),
                result.failReason() == null ? "" : " — " + result.failReason(),
                result.matchCount() == null ? "" : " — " + result.matchCount() + "판",
                took.toMillis() / 1000.0);
    }

    private void logSummary(Map<CollectStatus, Integer> counts) {
        log.info("이번 실행 결과 — {}", counts.isEmpty() ? "처리한 유저 없음" : counts);
    }

    private boolean sleepUntil(Instant until) {
        Duration left = Duration.between(clock.instant(), until);
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

    record Result(CollectStatus status, String failReason, Integer matchCount, String collectedAt) {
        Result(CollectStatus status, String failReason, Integer matchCount) {
            this(status, failReason, matchCount, null);
        }
    }
}
