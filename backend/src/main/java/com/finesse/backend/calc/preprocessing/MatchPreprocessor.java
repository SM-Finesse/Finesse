package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.collector.RawMatch.RawPlayer;
import com.finesse.backend.calc.collector.RawMatch.RawRound;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchRound;
import com.finesse.backend.calc.domain.PseudonymId;
import com.finesse.backend.calc.exception.AllMatchesExcludedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 원천 매치 → MatchHistory 변환 (설계서 5.9절 순서).
 * 정제(MatchValidator) → 스코프 발급 → 최신순으로 상대 유저 ID를 PseudonymId로 변환 → MatchHistory 생성.
 * 정제를 먼저 해야 버려질 매치의 상대가 스코프에 등록되지 않는다.
 */
@Component
public class MatchPreprocessor {

    /** 최신순, 같은 시각이면 matchId 오름차순 */
    static final Comparator<RawMatch> NEWEST_FIRST =
            Comparator.comparing(RawMatch::playedAt, Comparator.reverseOrder())
                    .thenComparing(RawMatch::matchId);

    private final MatchValidator validator;
    private final MatchScopedPseudonymizer pseudonymizer;

    public MatchPreprocessor(MatchValidator validator, MatchScopedPseudonymizer pseudonymizer) {
        this.validator = validator;
        this.pseudonymizer = pseudonymizer;
    }

    public PreprocessResult preprocess(List<RawMatch> rawMatches) {
        List<RawMatch> valid = new ArrayList<>();
        Map<MatchValidity, Integer> excluded = new EnumMap<>(MatchValidity.class);
        for (RawMatch raw : rawMatches) {
            MatchValidity validity = validator.validate(raw);
            if (validity == MatchValidity.VALID) {
                valid.add(raw);
            } else {
                excluded.merge(validity, 1, Integer::sum);
            }
        }
        if (!rawMatches.isEmpty() && valid.isEmpty()) {
            throw new AllMatchesExcludedException(rawMatches.size());
        }

        PseudonymScope scope = pseudonymizer.newScope();
        List<MatchHistory> matches = valid.stream()
                .sorted(NEWEST_FIRST)
                .map(raw -> toMatchHistory(raw, scope))
                .toList();
        return new PreprocessResult(matches, scope, excluded);
    }

    static MatchHistory toMatchHistory(RawMatch raw, PseudonymScope scope) {
        RawPlayer me = raw.me();
        RawPlayer opp = raw.opponent();
        PseudonymId opponentId = scope.resolve(opp.userId(), opp.usernameAtMatch());
        Double trGap = (me.trBefore() == null || opp.trBefore() == null)
                ? null : opp.trBefore() - me.trBefore();

        List<MatchRound> rounds = new ArrayList<>();
        for (int i = 0; i < raw.rounds().size(); i++) {
            RawRound r = raw.rounds().get(i);
            rounds.add(new MatchRound(i, r.myVs(), r.opponentVs(), trGap, r.meAlive()));
        }

        return new MatchHistory(
                raw.matchId(), raw.playedAt(), opponentId,
                me.pps(), me.apm(), me.vs(),
                opp.pps(), opp.apm(), opp.vs(),
                null, null, null, null,   // Statrank: 원천 응답에 없음 (설계서 8장)
                null, null, null, null,
                me.trBefore(), opp.trBefore(),
                "victory".equals(raw.result()) ? MatchResult.WIN : MatchResult.LOSE,
                rounds
        );
    }
}