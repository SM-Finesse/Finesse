package com.finesse.backend.calc.highlight;

import com.finesse.backend.calc.config.HighlightProperties;
import com.finesse.backend.calc.domain.HighlightZone;
import com.finesse.backend.calc.domain.JudgedMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 라이트 뷰 하이라이트 2단계 — 선정 (설계서 30.4절).
 * ① HIGH·LOW만 후보 ② 그룹 안 |표시값| 순위 ③ 점수 = |표시값| × 감점 계수^(순위 − 1)
 * ④ 점수 순 최대 3개 ⑤ 모자라면 NORMAL을 edgeGap 작은 순으로 채움 ⑥ 그래도 모자라면 있는 만큼.
 * 동점은 모두 고정 순서(입력 목록 순서)로 정한다.
 */
@Component
public class HighlightSelector {

    private static final int PICKS = 3;

    private final BigDecimal groupPenalty;

    public HighlightSelector(HighlightProperties properties) {
        this.groupPenalty = properties.groupPenalty();
    }

    /** @param metrics 1단계 판정 11개(고정 순서) @return pick1 → pick3, 0~3개 */
    public List<JudgedMetric> select(List<JudgedMetric> metrics) {
        Map<JudgedMetric, Integer> order = new HashMap<>();
        for (int i = 0; i < metrics.size(); i++) {
            order.put(metrics.get(i), i);
        }
        Comparator<JudgedMetric> fixed = Comparator.comparingInt(order::get);

        List<JudgedMetric> out = metrics.stream()
                .filter(m -> m.zone() == HighlightZone.HIGH || m.zone() == HighlightZone.LOW)
                .toList();

        // 그룹 안 순위 → 점수
        Map<JudgedMetric, BigDecimal> score = new HashMap<>();
        Map<String, List<JudgedMetric>> byGroup = new HashMap<>();
        for (JudgedMetric m : out) {
            byGroup.computeIfAbsent(m.group(), g -> new ArrayList<>()).add(m);
        }
        for (List<JudgedMetric> members : byGroup.values()) {
            members.sort(Comparator.comparing(HighlightSelector::shownAbs).reversed().thenComparing(fixed));
            for (int rank = 0; rank < members.size(); rank++) {
                JudgedMetric m = members.get(rank);
                score.put(m, shownAbs(m).multiply(groupPenalty.pow(rank)));
            }
        }

        List<JudgedMetric> picks = new ArrayList<>(out.stream()
                .sorted(Comparator.comparing((JudgedMetric m) -> score.get(m)).reversed().thenComparing(fixed))
                .limit(PICKS)
                .toList());

        if (picks.size() < PICKS) {
            metrics.stream()
                    .filter(m -> m.zone() == HighlightZone.NORMAL)
                    .sorted(Comparator.comparing(JudgedMetric::edgeGap).thenComparing(fixed))
                    .limit(PICKS - picks.size())
                    .forEach(picks::add);
        }
        return List.copyOf(picks);
    }

    /** 표시값의 절댓값 — 표시값은 소수 둘째 자리라 BigDecimal.valueOf로 정확히 되돌릴 수 있다 */
    private static BigDecimal shownAbs(JudgedMetric m) {
        return BigDecimal.valueOf(m.deviation()).abs();
    }
}