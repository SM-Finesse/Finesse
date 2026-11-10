package com.finesse.backend.service;

import com.finesse.backend.client.TetrioClient;
import com.finesse.backend.config.PaperProperties;
import com.finesse.backend.dto.LlmLightComment;
import com.finesse.backend.dto.PaperInput;
import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.exception.InvalidUsernameException;
import com.finesse.backend.exception.ServerBusyException;
import com.finesse.backend.exception.TetrioApiException;
import com.finesse.backend.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * POST /api/paper/inputs — 논문 실험 입력 스냅샷. 유저마다 calc를 캐시 없이 새로 돌리고, 서비스와 같은 변환으로
 * light_picks(pick1~3)·heavy_chapters(rivals 제외 7챕터)를 만든다. 유저 하나가 실패해도 나머지는 그대로 돌려준다.
 */
@Service
@ConditionalOnProperty(prefix = "app.paper", name = "enabled", havingValue = "true")
public class PaperInputService {

    private static final Logger log = LoggerFactory.getLogger(PaperInputService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EXCLUDED_CHAPTER = "rivals";

    private final StatsService statsService;
    private final TetrioClient tetrioClient;
    private final String calcCommit;

    public PaperInputService(StatsService statsService, TetrioClient tetrioClient, PaperProperties props) {
        this.statsService = statsService;
        this.tetrioClient = tetrioClient;
        this.calcCommit = props.calcCommit().isEmpty() ? gitHead() : props.calcCommit();
        log.info("논문용 입력 스냅샷 calc_commit={}", calcCommit);
    }

    public List<PaperInput> snapshot(List<String> usernames) {
        List<PaperInput> results = new ArrayList<>();
        for (String username : usernames) {
            results.add(snapshotOne(username));
        }
        return results;
    }

    PaperInput snapshotOne(String username) {
        String normalized;
        try {
            normalized = Usernames.normalize(username);
        } catch (InvalidUsernameException e) {
            return PaperInput.failed(username, "INVALID_USERNAME", "유저명 형식이 올바르지 않습니다");
        }
        StatsResponse stats;
        try {
            stats = statsService.computeUncached(normalized);
        } catch (UserNotFoundException e) {
            return PaperInput.failed(normalized, "USER_NOT_FOUND", "존재하지 않는 TETR.IO 유저입니다");
        } catch (ServerBusyException e) {
            return PaperInput.failed(normalized, "SERVER_BUSY", "수집 동시 처리 상한 — 잠시 후 이 유저만 다시 요청");
        } catch (TetrioApiException e) {
            log.warn("논문용 입력 스냅샷 TETR.IO 실패: {}", normalized, e);
            return PaperInput.failed(normalized, "TETRIO_API_UNAVAILABLE", "TETR.IO 조회 실패");
        } catch (RuntimeException e) {
            log.error("논문용 입력 스냅샷 계산 실패: {}", normalized, e);
            return PaperInput.failed(normalized, "INTERNAL_ERROR", e.getClass().getSimpleName());
        }
        if (stats.coldStart()) {
            return PaperInput.failed(normalized, "INSUFFICIENT_MATCHES",
                    "분석 가능한 매치가 10판 미만입니다 (" + stats.matchCount() + "판)");
        }

        List<LlmLightComment.Item> picks = CommentService.lightCommentItems(stats);
        Map<String, Object> chapters = new LinkedHashMap<>(CommentService.buildChapterData(stats));
        chapters.remove(EXCLUDED_CHAPTER);
        StatsResponse.HighlightJudgement judgement = stats.highlightJudgement();
        String computedAt = stats.updatedAt() == null ? null
                : stats.updatedAt().atZone(KST).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new PaperInput(normalized, userId(normalized), computedAt, stats.matchCount(), picks, chapters,
                calcCommit, judgement == null ? null : judgement.rangesVersion(), null);
    }

    /** stats 응답에는 TETR.IO 내부 id가 없어 /users/{username}을 한 번 더 부른다 — 실패하면 user_id만 생략 */
    private String userId(String normalized) {
        try {
            return tetrioClient.fetchUserInfo(normalized, TetrioClient.newSessionId()).id();
        } catch (RuntimeException e) {
            log.warn("논문용 입력 스냅샷 user_id 조회 실패: {}", normalized, e);
            return null;
        }
    }

    /** 실행 디렉터리가 git 저장소면 HEAD 해시, 아니면 "unknown" — 배포 시엔 FINESSE_CALC_COMMIT으로 넣는 것을 권장 */
    private static String gitHead() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && line != null && line.matches("[0-9a-f]{40}")) {
                return line;
            }
        } catch (Exception ignored) {
            // git이 없거나 저장소가 아님
        }
        return "unknown";
    }
}
