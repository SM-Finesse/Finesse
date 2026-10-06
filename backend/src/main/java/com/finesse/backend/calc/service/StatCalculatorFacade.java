package com.finesse.backend.calc.service;

import com.finesse.backend.calc.calculator.CalculatorKey;
import com.finesse.backend.calc.calculator.CalculatorRegistry;
import com.finesse.backend.calc.collector.CollectionResult;
import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.collector.TetrIoCollector;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.config.AnalyticsProperties;
import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import com.finesse.backend.calc.domain.RivalryAggregate;
import com.finesse.backend.calc.domain.RivalryStats;
import com.finesse.backend.calc.domain.StatResult;
import com.finesse.backend.calc.exception.AllMatchesExcludedException;
import com.finesse.backend.calc.exception.TetrIoApiException;
import com.finesse.backend.calc.exception.TetrIoUserNotFoundException;
import com.finesse.backend.calc.metrics.AnalyticsMetrics;
import com.finesse.backend.calc.preprocessing.MatchPreprocessor;
import com.finesse.backend.calc.preprocessing.NicknamePseudonymizer;
import com.finesse.backend.calc.preprocessing.PreprocessResult;
import com.finesse.backend.calc.preprocessing.PseudonymScope;
import com.finesse.backend.calc.service.AnalysisOutcome.ColdStartReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * 분석 파이프라인 진입점 — 백엔드가 호출하는 유일한 공개 API (설계서 2.3·19.5절).
 * 요약 조회 → 예비 Cold Start → 매치 수집 → 최종 Cold Start → 정제·가명처리 → 계산 → 결과 조립.
 * PseudonymScope는 이 메서드 안에서만 쓰고 버린다.
 */
@Service
public class StatCalculatorFacade {

    private final TetrIoCollector collector;
    private final MatchPreprocessor preprocessor;
    private final CalculatorRegistry registry;
    private final NicknamePseudonymizer masker;
    private final AnalyticsProperties properties;
    private final Clock clock;
    private final AnalyticsMetrics metrics;

    @Autowired
    public StatCalculatorFacade(TetrIoCollector collector, MatchPreprocessor preprocessor,
                                CalculatorRegistry registry, NicknamePseudonymizer masker,
                                AnalyticsProperties properties, AnalyticsMetrics metrics) {
        this(collector, preprocessor, registry, masker, properties, Clock.systemUTC(), metrics);
    }

    StatCalculatorFacade(TetrIoCollector collector, MatchPreprocessor preprocessor,
                         CalculatorRegistry registry, NicknamePseudonymizer masker,
                         AnalyticsProperties properties, Clock clock) {
        this(collector, preprocessor, registry, masker, properties, clock, AnalyticsMetrics.noop());
    }

    StatCalculatorFacade(TetrIoCollector collector, MatchPreprocessor preprocessor,
                         CalculatorRegistry registry, NicknamePseudonymizer masker,
                         AnalyticsProperties properties, Clock clock, AnalyticsMetrics metrics) {
        this.collector = collector;
        this.preprocessor = preprocessor;
        this.registry = registry;
        this.masker = masker;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    public AnalysisOutcome analyze(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username이 비어 있습니다");
        }
        AnalysisOutcome outcome = doAnalyze(username);
        metrics.recordOutcome(outcome);
        return outcome;
    }

    private AnalysisOutcome doAnalyze(String username) {
        String user = username.trim().toLowerCase(Locale.ROOT);
        String session = TetrIoCollector.newSessionId();
        int threshold = properties.coldStartThreshold();

        // 1. 요약 조회 → 예비 Cold Start (3.2·3.3절)
        UserSummary summary;
        try {
            summary = collector.fetchSummary(user, session);
        } catch (TetrIoUserNotFoundException e) {
            return new AnalysisOutcome.UserNotFound(user);
        } catch (TetrIoApiException e) {
            return new AnalysisOutcome.CollectionFailed(CollectionStatus.FAILED);
        }
        if (summary.gamesPlayed() < threshold) {
            return new AnalysisOutcome.ColdStartBypass(summary, summary.gamesPlayed(), ColdStartReason.FEW_GAMES_TOTAL,
                    firstPageWinLoss(user, session, summary.gamesPlayed()));
        }

        // 2. 매치 수집 → 최종 Cold Start (3.4·3.7절)
        CollectionResult collection = collector.collectMatches(user, session, clock.instant());
        if (!collection.analyzable()) {
            return new AnalysisOutcome.CollectionFailed(collection.status());
        }
        if (collection.matches().size() < threshold) {
            return new AnalysisOutcome.ColdStartBypass(summary, collection.matches().size(),
                    ColdStartReason.FEW_GAMES_IN_YEAR, coldStartWinLoss(collection.matches()));
        }

        // 3. 정제·가명처리 (5장) — 정제 후 10판 미만이면 Cold Start와 같은 경로 (5.8절)
        PreprocessResult current;
        try {
            current = preprocessor.preprocess(collection.matches());
        } catch (AllMatchesExcludedException e) {
            return new AnalysisOutcome.ColdStartBypass(summary, 0, ColdStartReason.TOO_MANY_INVALID, null);
        }
        metrics.recordExcluded(current.excludedByReason());
        if (current.matches().size() < threshold) {
            return new AnalysisOutcome.ColdStartBypass(summary, current.matches().size(),
                    ColdStartReason.TOO_MANY_INVALID, winLoss(current.matches()));
        }
        List<MatchHistory> previous = preprocessPreviousWindow(collection.previousWindowMatches());

        // 4. 계산 (19.4절)
        AnalyticsContext context = new AnalyticsContext(current.matches(), previous);
        StatResult result = new StatResult(
                calculate(CalculatorKey.FANCY, context),
                calculate(CalculatorKey.DELTA, context),
                calculate(CalculatorKey.HIGHLIGHT, context),
                calculate(CalculatorKey.WIN_LOSS, context),
                calculate(CalculatorKey.PROFILE_DELTA, context),
                toRivalryStats(calculate(CalculatorKey.RIVALRY, context), current.scope()),
                calculate(CalculatorKey.SERIES, context)
        );

        AnalysisMeta meta = new AnalysisMeta(
                current.matches().size(), previous.size(), collection.partial(),
                current.excludedCount(), collection.droppedRecords(),
                (int) current.matches().stream().filter(MatchHistory::endedEarly).count(),
                (int) current.matches().stream().filter(m -> m.firstTo() == null).count());
        return new AnalysisOutcome.Analyzed(summary, result, meta);
    }

    // ── Cold Start 최근 승패 (12.2절, v3.6) ───────────────────────────

    /** 누적 10판 미만: records 첫 페이지만 받아 계산한다. 0판이면 호출하지 않고, 실패하면 null. */
    private RecentWinLossStats firstPageWinLoss(String user, String session, int gamesPlayed) {
        if (gamesPlayed <= 0) {
            return null;
        }
        try {
            return coldStartWinLoss(collector.collectFirstPage(user, session, clock.instant()));
        } catch (TetrIoApiException e) {
            return null;
        }
    }

    /** 수집한 매치를 정제한 뒤 승패를 계산한다. 전부 제외되면 null. */
    private RecentWinLossStats coldStartWinLoss(List<RawMatch> rawMatches) {
        if (rawMatches.isEmpty()) {
            return null;
        }
        try {
            return winLoss(preprocessor.preprocess(rawMatches).matches());
        } catch (AllMatchesExcludedException e) {
            return null;
        }
    }

    private RecentWinLossStats winLoss(List<MatchHistory> matches) {
        return matches.isEmpty() ? null : calculate(CalculatorKey.WIN_LOSS, new AnalyticsContext(matches, List.of()));
    }

    /** Calculator 실행 시간을 계산기별로 기록한다 (설계서 27.3절). */
    private <T> T calculate(CalculatorKey key, AnalyticsContext context) {
        return metrics.timeCalculation(key, () -> registry.<T>calculate(key, context));
    }

    /** 이전 구간은 구간 비교(11.11절)에만 쓰므로, 전부 제외되면 빈 목록으로 둔다. */
    private List<MatchHistory> preprocessPreviousWindow(List<RawMatch> previousRaw) {
        try {
            return preprocessor.preprocess(previousRaw).matches();
        } catch (AllMatchesExcludedException e) {
            return List.of();
        }
    }

    /** 마스킹 닉네임만 붙여 외부 노출용으로 바꾼다 — 원본 닉네임·유저 ID는 나가지 않는다 (11.12절). */
    private RivalryStats toRivalryStats(RivalryAggregate aggregate, PseudonymScope scope) {
        return RivalryStats.from(aggregate, id -> scope.resolveMaskedNickname(id, masker));
    }
}