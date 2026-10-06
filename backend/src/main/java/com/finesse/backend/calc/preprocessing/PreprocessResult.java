package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.domain.MatchHistory;

import java.util.List;
import java.util.Map;

/**
 * 전처리 결과 (설계서 5.8·5.9절).
 * matches는 최신순이다. scope는 같은 요청 안에서 Facade가 마스킹 닉네임 변환에만 사용하고 버린다.
 */
public record PreprocessResult(
        List<MatchHistory> matches,
        PseudonymScope scope,
        Map<MatchValidity, Integer> excludedByReason
) {
    public PreprocessResult {
        matches = List.copyOf(matches);
        excludedByReason = Map.copyOf(excludedByReason);
    }

    public int excludedCount() {
        return excludedByReason.values().stream().mapToInt(Integer::intValue).sum();
    }
}