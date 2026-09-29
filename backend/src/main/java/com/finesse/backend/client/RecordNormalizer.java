package com.finesse.backend.client;

import tools.jackson.databind.JsonNode;
import com.finesse.backend.model.CollectionResult;
import com.finesse.backend.model.NormalizedMatch;
import com.finesse.backend.model.RoundSample;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TETR.IO 원시 레코드 → 정규화 (데이터 수집 명세 4장 "레코드 구조", 6장 "방어적 파싱 규칙").
 *
 * ※ 임시 구현 — 데이터팀(위성훈, data-eng 브랜치)의 calc 모듈(MatchHistory/MatchRound 등)이 완성되면
 * 그쪽으로 교체 예정 (2026-09-29 팀 확인). 이 클래스가 만드는 NormalizedMatch가 MatchHistory에 대응.
 *
 * 핵심 규칙:
 *  - stub은 무시하고 그대로 사용한다 (4.2절 — 데이터와 무관).
 *  - "나"와 "상대"는 username이 아니라 otherusers[].id 로 구분한다 (6.3절 — 닉네임은 개명될 수 있어
 *    leaderboard[].username 기준으로 매칭하면 위험함).
 *  - extras.league 원소가 null인 매치는 TR 필드만 null 처리하고 stats 기반 지표는 그대로 계산한다 (6.2절).
 */
@Component
public class RecordNormalizer {

    public CollectionResult.Meta normalize(List<JsonNode> rawEntries, String selfUsernameLower,
                                            List<NormalizedMatch> outMatches) {
        int dropped = 0;
        int missingTr = 0;

        for (JsonNode entry : rawEntries) {
            try {
                NormalizedMatch m = normalizeOne(entry);
                if (m == null) {
                    dropped++;
                    continue;
                }
                if (!m.me().hasTr()) {
                    missingTr++;
                }
                outMatches.add(m);
            } catch (Exception e) {
                dropped++; // 6장 원칙 — 조용히 버리지 말고 개수를 남긴다
            }
        }
        return new CollectionResult.Meta(dropped, missingTr);
    }

    private NormalizedMatch normalizeOne(JsonNode entry) {
        String matchId = entry.path("_id").asText(null);
        Instant ts = Instant.parse(entry.path("ts").asText());
        String result = entry.path("extras").path("result").asText(null);

        // otherusers[].id 집합 — 이 집합에 속하지 않는 leaderboard 원소가 "나" (6.3절)
        Set<String> otherUserIds = new HashSet<>();
        for (JsonNode ou : entry.path("otherusers")) {
            String id = ou.path("id").asText(null);
            if (id != null) {
                otherUserIds.add(id);
            }
        }

        JsonNode leaderboard = entry.path("results").path("leaderboard");
        JsonNode meEntry = null;
        JsonNode oppEntry = null;
        for (JsonNode p : leaderboard) {
            String id = p.path("id").asText(null);
            if (id != null && otherUserIds.contains(id)) {
                oppEntry = p;
            } else if (meEntry == null) {
                meEntry = p;
            }
        }
        if (meEntry == null || oppEntry == null) {
            return null; // 1v1 리그 매치가 아니거나 구조가 예상과 다름 — 버림 (dropped_records에 카운트)
        }

        JsonNode leagueExtras = entry.path("extras").path("league");
        NormalizedMatch.Side meSide = buildSide(meEntry, leagueExtras, null, null);
        NormalizedMatch.Side oppSide = buildSide(oppEntry, leagueExtras,
                findOtherUsername(entry, oppEntry.path("id").asText(null)),
                oppEntry.path("username").asText(null));

        List<RoundSample> rounds = normalizeRounds(entry.path("results").path("rounds"), meEntry.path("id").asText(null));

        return new NormalizedMatch(matchId, ts, result, meSide, oppSide, rounds);
    }

    private NormalizedMatch.Side buildSide(JsonNode playerEntry, JsonNode leagueExtras,
                                            String usernameCurrent, String usernameAtMatch) {
        JsonNode stats = playerEntry.path("stats");
        Double apm = numOrNull(stats, "apm");
        Double pps = numOrNull(stats, "pps");
        Double vs = numOrNull(stats, "vsscore"); // 필드명 주의 — 매치 레코드는 vsscore (4.1-1절)
        int wins = playerEntry.path("wins").asInt();
        String rank = null;
        Double trBefore = null;
        Double trAfter = null;

        String id = playerEntry.path("id").asText(null);
        // 6.2절 — extras.league[uid]가 [null, null] 형태로 올 수 있음. 배열 원소 null을 전역 규칙으로 처리.
        if (id != null && leagueExtras.has(id)) {
            JsonNode pair = leagueExtras.path(id);
            if (pair.isArray() && pair.size() == 2) {
                JsonNode before = pair.get(0);
                JsonNode after = pair.get(1);
                if (before != null && !before.isNull()) {
                    trBefore = numOrNull(before, "tr");
                    rank = textOrNull(before, "rank"); // "z"도 그대로 둔다 — 6.1절, 언랭크일 뿐 데이터는 정상
                }
                if (after != null && !after.isNull()) {
                    trAfter = numOrNull(after, "tr");
                }
            }
        }

        return new NormalizedMatch.Side(id, wins, apm, pps, vs, trBefore, trAfter, rank, usernameCurrent, usernameAtMatch);
    }

    private String findOtherUsername(JsonNode entry, String opponentId) {
        if (opponentId == null) {
            return null;
        }
        for (JsonNode ou : entry.path("otherusers")) {
            if (opponentId.equals(ou.path("id").asText(null))) {
                return ou.path("username").asText(null); // 현재 닉네임 (6.3절)
            }
        }
        return null;
    }

    private List<RoundSample> normalizeRounds(JsonNode roundsNode, String selfId) {
        List<RoundSample> rounds = new ArrayList<>();
        if (!roundsNode.isArray()) {
            return rounds;
        }
        int index = 1;
        for (JsonNode roundArr : roundsNode) {
            RoundSample.Sample meSample = null;
            RoundSample.Sample oppSample = null;
            boolean meAlive = false;
            boolean oppAlive = false;
            for (JsonNode p : roundArr) {
                RoundSample.Sample sample = new RoundSample.Sample(
                        numOrNull(p.path("stats"), "apm"),
                        numOrNull(p.path("stats"), "pps"),
                        numOrNull(p.path("stats"), "vsscore")
                );
                if (selfId != null && selfId.equals(p.path("id").asText(null))) {
                    meSample = sample;
                    meAlive = p.path("alive").asBoolean(false);
                } else {
                    oppSample = sample;
                    oppAlive = p.path("alive").asBoolean(false);
                }
            }
            rounds.add(new RoundSample(index++, meAlive, oppAlive, meSample, oppSample));
        }
        return rounds;
    }

    private static Double numOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asDouble() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}
