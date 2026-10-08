package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.collector.RawMatch;
import com.finesse.backend.calc.collector.RawMatch.RawPlayer;
import com.finesse.backend.calc.collector.RawMatch.RawRound;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 원천 매치 정제 (설계서 5.7·5.8절).
 * ABORTED·DISCONNECTED·FORFEIT는 TETR.IO 응답에서 판정 필드가 확인되지 않아 아직 판정하지 않는다.
 * 매치 당시 TR·등급 결측은 제외 사유가 아니다 — TR 지표와 경기 형식 판정에서만 영향을 준다.
 */
@Component
public class MatchValidator {

    static final double MIN_PPS = 0.1;
    /** APM = 0인 매치가 정상으로 인정되는 최소 PPS (5.7절, v3.5) */
    static final double MIN_PPS_WITHOUT_ATTACK = 0.2;
    /** 정상 종료 매치의 승자 승수 — 3·5·7선승 (5.11절) */
    static final Set<Integer> FINISHED_WINS = Set.of(3, 5, 7);

    public MatchValidity validate(RawMatch match) {
        if (!isKnownResult(match.result())
                || !hasValidStats(match.me()) || !hasValidStats(match.opponent())) {
            return MatchValidity.INVALID_STATS;
        }
        if (match.rounds() == null || match.rounds().isEmpty()
                || match.rounds().stream().anyMatch(MatchValidator::isBrokenRound)) {
            return MatchValidity.INSUFFICIENT_ROUND_DATA;
        }
        if (!FINISHED_WINS.contains(winnerWins(match))) {
            return MatchValidity.INCOMPLETE_MATCH;
        }
        return MatchValidity.VALID;
    }

    static boolean isKnownResult(String result) {
        return "victory".equals(result) || "defeat".equals(result);
    }

    /**
     * 식별 정보가 있고 PPS ≥ 0.1, VS ≥ 0, APM ≥ 0이어야 한다.
     * APM = 0은 PPS ≥ 0.2일 때만 정상(공격하지 못하고 진 판)으로 본다 (v3.5).
     */
    static boolean hasValidStats(RawPlayer p) {
        if (p == null || p.userId() == null || p.usernameAtMatch() == null
                || p.apm() == null || p.pps() == null || p.vs() == null) {
            return false;
        }
        if (p.apm() < 0.0 || p.pps() < MIN_PPS || p.vs() < 0.0) {
            return false;
        }
        return p.apm() > 0.0 || p.pps() >= MIN_PPS_WITHOUT_ATTACK;
    }

    static boolean isBrokenRound(RawRound r) {
        return r == null || r.myVs() == null || r.opponentVs() == null
                || r.myVs() < 0.0 || r.opponentVs() < 0.0;
    }

    /** 결과(result)상 이긴 선수가 이긴 라운드 수. 라운드 종료 시 alive인 쪽이 그 라운드의 승자다. */
    static int winnerWins(RawMatch match) {
        boolean iWon = "victory".equals(match.result());
        int wins = 0;
        for (RawRound r : match.rounds()) {
            if (r.meAlive() == iWon) {
                wins++;
            }
        }
        return wins;
    }
}
