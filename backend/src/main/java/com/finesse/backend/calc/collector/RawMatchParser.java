package com.finesse.backend.calc.collector;

import com.finesse.backend.calc.collector.RawMatch.RawPlayer;
import com.finesse.backend.calc.collector.RawMatch.RawRound;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * records/league/recent 응답 JSON → RawMatch (설계서 부록 B).
 * 본인/상대는 username이 아니라 otherusers[].id로 구분한다(닉네임은 개명될 수 있음).
 * 구조가 예상과 다른 레코드는 버리고 개수를 센다 — 조용히 무시하지 않는다.
 */
@Component
public class RawMatchParser {

    /** @param data 응답의 data 노드 ({ entries: [...] }) */
    public RecordPage parsePage(JsonNode data) {
        JsonNode entries = data.path("entries");
        List<RawMatch> matches = new ArrayList<>();
        int dropped = 0;
        if (!entries.isArray() || entries.isEmpty()) {
            return new RecordPage(matches, 0, null);
        }
        for (JsonNode entry : entries) {
            try {
                RawMatch m = parse(entry);
                if (m == null) {
                    dropped++;
                } else {
                    matches.add(m);
                }
            } catch (RuntimeException e) {
                dropped++;
            }
        }
        return new RecordPage(matches, dropped, nextCursor(entries.get(entries.size() - 1)));
    }

    /** 1v1 리그 매치 구조가 아니면 null */
    RawMatch parse(JsonNode entry) {
        Set<String> otherUserIds = new HashSet<>();
        for (JsonNode ou : entry.path("otherusers")) {
            String id = text(ou, "id");
            if (id != null) {
                otherUserIds.add(id);
            }
        }

        JsonNode meNode = null;
        JsonNode oppNode = null;
        for (JsonNode p : entry.path("results").path("leaderboard")) {
            String id = text(p, "id");
            if (id != null && otherUserIds.contains(id)) {
                oppNode = p;
            } else if (meNode == null) {
                meNode = p;
            }
        }
        if (meNode == null || oppNode == null) {
            return null;
        }

        JsonNode league = entry.path("extras").path("league");
        RawPlayer me = player(meNode, league);
        RawPlayer opp = player(oppNode, league);

        return new RawMatch(
                text(entry, "_id"),
                Instant.parse(entry.path("ts").asString()),
                entry.path("extras").path("result").asString(null),
                me,
                opp,
                rounds(entry.path("results").path("rounds"), me.userId())
        );
    }

    private RawPlayer player(JsonNode node, JsonNode league) {
        String id = text(node, "id");
        JsonNode stats = node.path("stats");
        return new RawPlayer(
                id,
                text(node, "username"),
                number(stats, "apm"),
                number(stats, "pps"),
                number(stats, "vsscore"),
                trBefore(league, id)
        );
    }

    /** extras.league[userId] = [매치 전, 매치 후]. 매치 전 값의 tr만 쓴다 (1.1절 ⑧). [null, null]이면 null. */
    private Double trBefore(JsonNode league, String userId) {
        if (userId == null || !league.has(userId)) {
            return null;
        }
        JsonNode pair = league.path(userId);
        if (!pair.isArray() || pair.size() < 1) {
            return null;
        }
        JsonNode before = pair.get(0);
        return (before == null || before.isNull()) ? null : number(before, "tr");
    }

    /** results.rounds[] — 라운드마다 두 선수의 {id, alive, stats} 배열 */
    private List<RawRound> rounds(JsonNode roundsNode, String myId) {
        List<RawRound> rounds = new ArrayList<>();
        if (!roundsNode.isArray()) {
            return rounds;
        }
        for (JsonNode round : roundsNode) {
            JsonNode mine = null;
            JsonNode theirs = null;
            for (JsonNode p : round) {
                if (myId != null && myId.equals(text(p, "id"))) {
                    mine = p;
                } else {
                    theirs = p;
                }
            }
            rounds.add(new RawRound(
                    mine != null && mine.path("alive").asBoolean(false),
                    theirs != null && theirs.path("alive").asBoolean(false),
                    mine == null ? null : number(mine.path("stats"), "vsscore"),
                    theirs == null ? null : number(theirs.path("stats"), "vsscore")
            ));
        }
        return rounds;
    }

    /** 마지막 레코드의 p {pri, sec, ter} → "pri:sec:ter", 없으면 null */
    private String nextCursor(JsonNode lastEntry) {
        JsonNode p = lastEntry.path("p");
        if (p.isMissingNode() || p.isNull()) {
            return null;
        }
        return p.path("pri").asString() + ":" + p.path("sec").asString() + ":" + p.path("ter").asString();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asString() : null;
    }

    private static Double number(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asDouble() : null;
    }
}