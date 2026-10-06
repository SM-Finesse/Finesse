package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.collector.RawMatch.RawPlayer;
import com.finesse.backend.calc.collector.RawMatch.RawRound;
import org.springframework.stereotype.Component;

/**
 * 원천 매치 정제 (설계서 5.7·5.8절).
 * ABORTED·DISCONNECTED·FORFEIT는 TETR.IO 응답에서 판정 필드가 확인되지 않아 아직 판정하지 않는다.
 * 매치 당시 TR 결측은 제외 사유가 아니다 — TR 지표에서만 빠진다.
 */
@Component
public class MatchValidator {

    static final double MIN_PPS = 0.1;

    public MatchValidity validate(RawMatch match) {
        if (!isKnownResult(match.result())
                || !hasValidStats(match.me()) || !hasValidStats(match.opponent())) {
            return MatchValidity.INVALID_STATS;
        }
        if (match.rounds() == null || match.rounds().isEmpty()
                || match.rounds().stream().anyMatch(MatchValidator::isBrokenRound)) {
            return MatchValidity.INSUFFICIENT_ROUND_DATA;
        }
        return MatchValidity.VALID;
    }

    static boolean isKnownResult(String result) {
        return "victory".equals(result) || "defeat".equals(result);
    }

    /** APM > 0, PPS ≥ 0.1, VS ≥ 0이고 식별 정보가 있어야 한다. */
    static boolean hasValidStats(RawPlayer p) {
        return p != null
                && p.userId() != null && p.usernameAtMatch() != null
                && p.apm() != null && p.apm() > 0.0
                && p.pps() != null && p.pps() >= MIN_PPS
                && p.vs() != null && p.vs() >= 0.0;
    }

    static boolean isBrokenRound(RawRound r) {
        return r == null || r.myVs() == null || r.opponentVs() == null
                || r.myVs() < 0.0 || r.opponentVs() < 0.0;
    }
}