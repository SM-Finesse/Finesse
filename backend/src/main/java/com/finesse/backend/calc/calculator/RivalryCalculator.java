package com.finesse.backend.calc.calculator;

import com.finesse.backend.calc.domain.AnalyticsContext;
import com.finesse.backend.calc.domain.MatchHistory;
import com.finesse.backend.calc.domain.PseudonymId;
import com.finesse.backend.calc.domain.RivalBadge;
import com.finesse.backend.calc.domain.RivalOpponentAggregate;
import com.finesse.backend.calc.domain.RivalryAggregate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 반복 조우 상대 통계 (설계서 11.12절).
 * opponentId(PseudonymId)로만 그룹핑하며 원본 닉네임·PseudonymScope에 접근하지 않는다.
 * 대상은 현재 구간(context.matches())뿐이다.
 */
@Component
public class RivalryCalculator implements AnalyticsCalculator<RivalryAggregate> {

    static final int MIN_ENCOUNTERS = 5;
    static final double NEMESIS_MAX_WIN_RATE = 40.0;
    static final double DOMINANT_MIN_WIN_RATE = 65.0;
    static final double CLOSE_MIN_WIN_RATE = 45.0;
    static final double CLOSE_MAX_WIN_RATE = 55.0;

    /** matchCount 내림차순 → opponentId 오름차순 (결정론적 정렬) */
    static final Comparator<RivalOpponentAggregate> BY_MATCH_COUNT_DESC =
            Comparator.comparingInt(RivalOpponentAggregate::matchCount).reversed()
                    .thenComparing(a -> a.opponentId().value());

    @Override
    public CalculatorKey key() {
        return CalculatorKey.RIVALRY;
    }

    @Override
    public RivalryAggregate calculate(AnalyticsContext context) {
        Map<PseudonymId, List<MatchHistory>> byOpponent = context.matches().stream()
                .collect(Collectors.groupingBy(MatchHistory::opponentId));

        List<RivalOpponentAggregate> rivals = byOpponent.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_ENCOUNTERS)
                .map(e -> aggregate(e.getKey(), e.getValue()))
                .sorted(BY_MATCH_COUNT_DESC)
                .toList();
        if (rivals.isEmpty()) {
            return RivalryAggregate.empty();
        }

        // 동률이면 rivals 정렬 순서상 앞선 상대를 선택한다(min/max는 동률 시 먼저 나온 원소를 유지)
        RivalOpponentAggregate nemesis = rivals.stream()
                .filter(r -> r.winRate() <= NEMESIS_MAX_WIN_RATE)
                .min(Comparator.comparingDouble(RivalOpponentAggregate::winRate))
                .orElse(null);
        RivalOpponentAggregate dominant = rivals.stream()
                .filter(r -> r.winRate() >= DOMINANT_MIN_WIN_RATE)
                .max(Comparator.comparingDouble(RivalOpponentAggregate::winRate))
                .orElse(null);

        return new RivalryAggregate(rivals, nemesis, dominant);
    }

    static RivalOpponentAggregate aggregate(PseudonymId opponentId, List<MatchHistory> matches) {
        int wins = (int) matches.stream().filter(MatchHistory::isWin).count();
        int losses = matches.size() - wins;
        double winRate = wins * 100.0 / matches.size();
        return new RivalOpponentAggregate(opponentId, matches.size(), wins, losses, winRate, classify(winRate));
    }

    static RivalBadge classify(double winRate) {
        if (winRate <= NEMESIS_MAX_WIN_RATE) return RivalBadge.NEMESIS;
        if (winRate >= DOMINANT_MIN_WIN_RATE) return RivalBadge.DOMINANT;
        if (winRate >= CLOSE_MIN_WIN_RATE && winRate <= CLOSE_MAX_WIN_RATE) return RivalBadge.CLOSE;
        return RivalBadge.NONE;
    }
}