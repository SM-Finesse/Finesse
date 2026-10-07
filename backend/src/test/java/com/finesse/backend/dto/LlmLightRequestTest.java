package com.finesse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * light 요청이 LLM/AI 파트 설계 v1.2 5.2절 스키마(llm 브랜치 llm-server/app/schemas.py)와 키까지 같은지.
 * 추론 서버는 모르는 필드가 하나라도 있으면 요청을 거절한다(extra=forbid).
 */
class LlmLightRequestTest {

    // 앱 설정(application.yml spring.jackson)과 같게 — SNAKE_CASE, null 생략
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static StatsResponse stats(Double comebackRate, Double comebackRateAgainst, Double strengthSplit) {
        StatsResponse.FixedMetrics fixed = new StatsResponse.FixedMetrics(0.5, List.of(21323.0, 21417.0), List.of("W", "L"));
        StatsResponse.DeltaMetrics delta = new StatsResponse.DeltaMetrics(
                12.5,
                new StatsResponse.PlaystyleRelative(0.1, -0.22, -0.08, -0.17),
                new StatsResponse.Attack(-0.05, -1.34),
                new StatsResponse.Defense(-0.07, -2.56),
                strengthSplit, comebackRate, comebackRateAgainst, 0.55);
        return new StatsResponse("icly", false, 300, Instant.now(), null, fixed, delta, null, null, Map.of());
    }

    private static Set<String> keys(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    @Test
    void v1_2_스키마_키만_보낸다() {
        JsonNode json = mapper.valueToTree(LlmLightRequest.from(stats(0.40, 0.58, 0.03)));

        assertThat(keys(json)).containsExactlyInAnyOrder("fixed_metrics", "delta_metrics");
        assertThat(keys(json.get("fixed_metrics"))).containsExactlyInAnyOrder("win_rate", "tr_trend");
        assertThat(keys(json.get("delta_metrics"))).containsExactlyInAnyOrder(
                "playstyle_relative", "attack", "defense", "strength_split", "delta_comeback", "session_vs_slope");
        assertThat(keys(json.get("delta_metrics").get("playstyle_relative")))
                .containsExactlyInAnyOrder("delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds");
        assertThat(keys(json.get("delta_metrics").get("attack"))).containsExactlyInAnyOrder("delta_app", "delta_weighted_app");
        assertThat(keys(json.get("delta_metrics").get("defense"))).containsExactlyInAnyOrder("delta_vs_apm", "delta_cheese_index");
    }

    @Test
    void delta_comeback은_역전률에서_역전당한_비율을_뺀_값() {
        JsonNode json = mapper.valueToTree(LlmLightRequest.from(stats(0.40, 0.58, 0.03)));

        assertThat(json.get("delta_metrics").get("delta_comeback").asDouble()).isEqualTo(0.40 - 0.58);
    }

    @Test
    void 비율이_하나라도_없으면_delta_comeback과_빈_후보는_생략() {
        JsonNode json = mapper.valueToTree(LlmLightRequest.from(stats(0.40, null, null)));

        assertThat(json.get("delta_metrics").has("delta_comeback")).isFalse();
        assertThat(json.get("delta_metrics").has("strength_split")).isFalse();
    }
}
