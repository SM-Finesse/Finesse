package com.finesse.backend.calc.domain;

import java.util.List;

public record AnalyticsContext(
        List<MatchHistory> matches,
        List<MatchHistory> previousWindowMatches
) {
    public AnalyticsContext {
        matches = List.copyOf(matches);
        previousWindowMatches = previousWindowMatches == null
                ? List.of() : List.copyOf(previousWindowMatches);
    }
}