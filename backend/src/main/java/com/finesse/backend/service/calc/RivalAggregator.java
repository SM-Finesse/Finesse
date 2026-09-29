package com.finesse.backend.service.calc;

import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.model.NormalizedMatch;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * FR-08 라이벌(자주 만난 상대) 집계 — 유저 ID 기준(닉네임 개명 문제 회피, 데이터 수집 명세 6.3절),
 * 조우 횟수 내림차순, 동률 시 최근 대전 시각 우선. 최근 300판 집계, 최소 1판부터 전체 포함.
 *
 * TODO(협의 필요): /stats 엔드포인트에 rivals 페이지 번호 쿼리 파라미터가 아직 명세에 없어서
 * 지금은 항상 1페이지(20명)만 반환한다. 프론트와 페이지네이션 파라미터 이름을 정하고 나면 반영.
 */
@Component
public class RivalAggregator {

    private static final int PAGE_SIZE = 20;

    public StatsResponse.Rivals aggregate(List<NormalizedMatch> matches) {
        record Agg(String id, String username, int matches, int wins, int losses, java.time.Instant lastMatchAt) {
        }

        Map<String, List<NormalizedMatch>> byOpponent = new LinkedHashMap<>();
        for (NormalizedMatch m : matches) {
            String id = m.opp().id();
            if (id == null) continue;
            byOpponent.computeIfAbsent(id, k -> new ArrayList<>()).add(m);
        }

        List<Agg> aggregates = new ArrayList<>();
        for (var entry : byOpponent.entrySet()) {
            List<NormalizedMatch> ms = entry.getValue();
            int wins = (int) ms.stream().filter(m -> "victory".equals(m.result())).count();
            int losses = ms.size() - wins;
            java.time.Instant last = ms.stream().map(NormalizedMatch::ts).max(java.time.Instant::compareTo).orElse(null);
            String username = ms.get(0).opp().usernameCurrent();
            if (username == null) {
                username = ms.get(0).opp().usernameAtMatch();
            }
            aggregates.add(new Agg(entry.getKey(), username, ms.size(), wins, losses, last));
        }

        aggregates.sort(Comparator.comparingInt(Agg::matches).reversed()
                .thenComparing(Comparator.comparing(Agg::lastMatchAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()));

        List<StatsResponse.RivalItem> page = aggregates.stream()
                .limit(PAGE_SIZE)
                .map(a -> new StatsResponse.RivalItem(
                        a.username() != null ? NicknameMasker.mask(a.username()) : "?",
                        a.matches(), a.wins(), a.losses(), a.lastMatchAt()))
                .toList();

        return new StatsResponse.Rivals(page, 1, PAGE_SIZE, aggregates.size());
    }
}
