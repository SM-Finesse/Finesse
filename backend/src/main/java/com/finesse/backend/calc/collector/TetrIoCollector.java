package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.config.CollectorProperties;
import com.finesse.backend.calc.exception.TetrIoApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1년 이내 랭크 매치 수집 (설계서 3.1·3.4·3.7절).
 * 최신 매치부터 페이지를 이어 받으며, 1년 컷오프보다 오래된 매치를 만나면 그 페이지에서 멈춘다.
 * 현재 구간(최대 300판) 다음으로 이전 구간(최대 300판)까지 받아 profile_window_delta(11.11절)에 쓴다.
 */
@Component
public class TetrIoCollector {

    private final TetrIoApi api;
    private final CollectorProperties properties;

    public TetrIoCollector(TetrIoApi api, CollectorProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    /** 분석 요청 1건 동안 모든 호출에 같은 X-Session-ID를 쓴다 (3.5절). */
    public static String newSessionId() {
        return "finesse-calc-" + UUID.randomUUID();
    }

    public UserSummary fetchSummary(String username, String sessionId) {
        return api.fetchLeagueSummary(username, sessionId);
    }

    public CollectionResult collectMatches(String username, String sessionId, Instant now) {
        Instant cutoff = now.minus(properties.maxAgeDays(), ChronoUnit.DAYS);
        int windowSize = properties.maxTotalMatches();
        int maxMatches = windowSize * 2;           // 현재 구간 + 이전 구간
        int maxPageCalls = properties.maxPages() * 2;

        Map<String, RawMatch> collected = new LinkedHashMap<>(); // matchId 중복 제거, 최신순 유지
        int requested = 0;
        int successful = 0;
        int failed = 0;
        int dropped = 0;
        boolean reachedCutoff = false;
        String cursor = null;

        while (requested < maxPageCalls && collected.size() < maxMatches) {
            requested++;
            RecordPage page;
            try {
                page = api.fetchRecentRecords(username, sessionId, cursor, properties.maxMatchesPerPage());
            } catch (TetrIoApiException e) {
                failed++;
                break; // 커서가 끊기므로 다음 페이지를 이어 받을 수 없다
            }
            successful++;
            dropped += page.droppedRecords();

            for (RawMatch m : page.matches()) {
                if (m.playedAt().isBefore(cutoff)) {
                    reachedCutoff = true;
                    break;
                }
                collected.putIfAbsent(m.matchId(), m);
                if (collected.size() >= maxMatches) {
                    break;
                }
            }
            if (reachedCutoff || page.matches().isEmpty() || page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }

        List<RawMatch> all = new ArrayList<>(collected.values());
        List<RawMatch> current = all.subList(0, Math.min(windowSize, all.size()));
        List<RawMatch> previous = all.size() > windowSize ? all.subList(windowSize, all.size()) : List.of();

        return new CollectionResult(current, previous,
                status(successful, failed, current.size(), windowSize),
                requested, successful, failed, dropped, reachedCutoff);
    }

    /**
     * 3.7절 판정 순서. 현재 구간(300판)을 다 채운 뒤 이전 구간 페이지에서만 실패했다면
     * 분석 대상은 온전하므로 COMPLETE로 본다(이전 구간만 짧아짐).
     */
    CollectionStatus status(int successful, int failed, int currentSize, int windowSize) {
        if (successful == 0) return CollectionStatus.FAILED;
        if (failed == 0 || currentSize >= windowSize) return CollectionStatus.COMPLETE;
        if (currentSize >= properties.minPartialMatches()) return CollectionStatus.PARTIAL;
        return CollectionStatus.INSUFFICIENT;
    }
}