package com.finesse.backend.service;

import com.finesse.backend.calc.domain.HighlightJudgement;
import com.finesse.backend.calc.domain.JudgedMetric;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 학습 데이터 수집(collector 프로파일 전용) — 서비스가 LLM에 보내려고 조립하는 요청을 그대로 만든다.
 * 재조립하지 않는다: light는 CommentService.getLight와 같은 LlmLightRequest.from(stats),
 * heavy는 CommentService.getHeavyStream과 같은 buildChapterData(stats) → LlmHeavyChapterRequest(chapterId, data).
 * 라이트 개별·기본 코멘트 요청(light-v5.6d)은 1번 서버 CommentService.lightCommentItems와 같은 규칙으로
 * calc 하이라이트 선정 결과(picks)에서 만든다(10/27).
 */
@Component
@Profile("collector")
public class TrainingPayloadBuilder {

    /** heavy 수집 챕터 — rivals는 입력 스키마 미결이라 이번 범위 밖(작업지시서 3.3절) */
    public static final List<String> HEAVY_CHAPTERS = List.of(
            "tr_trend", "playstyle", "attack", "defense", "strength_split", "comeback_rate", "session_vs_slope");

    private final StatCalculatorFacade facade;
    private final StatsService statsService;

    public TrainingPayloadBuilder(StatCalculatorFacade facade, StatsService statsService) {
        this.facade = facade;
        this.statsService = statsService;
    }

    /** 서비스와 같은 규칙(소문자·3~16자 영문·숫자·_·-) — 틀리면 IllegalArgumentException */
    public static String normalize(String raw) {
        return Usernames.normalize(raw);
    }

    /** TETR.IO 수집·계산 — 요약 1회 + 기록 최대 3회(300판에서 멈춤), X-Session-ID는 calc가 건마다 새로 만든다 */
    public AnalysisOutcome analyze(String normalized) {
        return facade.analyze(normalized);
    }

    public Payloads payloads(String normalized, AnalysisOutcome.Analyzed analyzed) {
        StatsResponse stats = statsService.analyzedResponse(normalized, analyzed);
        Map<String, Object> chapterData = CommentService.buildChapterData(stats);
        Map<String, LlmHeavyChapterRequest> heavy = new LinkedHashMap<>();
        for (String chapterId : HEAVY_CHAPTERS) {
            heavy.put(chapterId, new LlmHeavyChapterRequest(chapterId, chapterData.get(chapterId)));
        }
        HighlightJudgement judgement = analyzed.result() == null ? null : analyzed.result().highlightJudgement();
        return new Payloads(LlmLightRequest.from(stats), heavy, stats.matchCount(), judgement,
                lightItems(judgement, stats.deltaMetrics()));
    }

    /**
     * 라이트 개별·기본 코멘트 요청 원소 {stat, value, zone} — 1번 서버 CommentService.lightCommentItems와 같은 규칙.
     * 영역 high·normal·low → "높음"·"보통"·"낮음", 값은 화면과 같은 delta_metrics 원값(delta_comeback도 축소값이 아니라 원값).
     * 영역이 none이거나 원값이 없는 지표는 뺀다(LLM 서버가 422로 거절하므로).
     */
    static List<LightItem> lightItems(HighlightJudgement judgement, StatsResponse.DeltaMetrics delta) {
        if (judgement == null) {
            return List.of();
        }
        List<LightItem> items = new ArrayList<>();
        for (JudgedMetric pick : judgement.picks()) {
            String zone = switch (pick.zone()) {
                case HIGH -> "높음";
                case NORMAL -> "보통";
                case LOW -> "낮음";
                case NONE -> null;
            };
            Double value = displayValue(delta, pick.key());
            if (zone != null && value != null && Double.isFinite(value)
                    && items.stream().noneMatch(i -> i.stat().equals(pick.key()))) {
                items.add(new LightItem(pick.key(), value, zone));
            }
        }
        return items.size() > 3 ? List.copyOf(items.subList(0, 3)) : List.copyOf(items);
    }

    /** 화면(delta_metrics)에 보이는 원값 — 후보 11개 키 → stats 필드 (1번 서버 CommentService.displayValue와 같음) */
    static Double displayValue(StatsResponse.DeltaMetrics d, String key) {
        if (d == null) {
            return null;
        }
        StatsResponse.PlaystyleRelative p = d.playstyleRelative();
        return switch (key) {
            case "delta_opener" -> p == null ? null : p.deltaOpener();
            case "delta_plonk" -> p == null ? null : p.deltaPlonk();
            case "delta_stride" -> p == null ? null : p.deltaStride();
            case "delta_inf_ds" -> p == null ? null : p.deltaInfDs();
            case "delta_app" -> d.attack() == null ? null : d.attack().deltaApp();
            case "delta_weighted_app" -> d.attack() == null ? null : d.attack().deltaWeightedApp();
            case "delta_vs_apm" -> d.defense() == null ? null : d.defense().deltaVsApm();
            case "delta_cheese_index" -> d.defense() == null ? null : d.defense().deltaCheeseIndex();
            case "strength_split" -> d.strengthSplit();
            case "delta_comeback" -> d.deltaComeback();
            case "session_vs_slope" -> d.sessionVsSlope();
            default -> null;
        };
    }

    /** 라이트 개별 코멘트 요청 1건 — LLM 연동 명세 light-v5.6d 2.1절 {stat, value, zone} */
    public record LightItem(String stat, double value, String zone) {
    }

    /**
     * 1번 서버가 LLM에 보내는 요청 그대로 — 그 밖의 값(recent_form 등)은 담지 않는다
     * (PM 10/12: 학습 데이터는 현재 1번 서버 형태를 따른다).
     *
     * @param matchCount 정제 후 유효 매치 수(메타)
     * @param judgement  calc 하이라이트 판정·선정 결과(v4.3~) — 계산하지 못했으면 null
     * @param lightItems 라이트 개별·기본 코멘트 요청 원소(0~3개, picks 순서)
     */
    public record Payloads(LlmLightRequest light, Map<String, LlmHeavyChapterRequest> heavy, int matchCount,
                           HighlightJudgement judgement, List<LightItem> lightItems) {
        public Payloads(LlmLightRequest light, Map<String, LlmHeavyChapterRequest> heavy, int matchCount) {
            this(light, heavy, matchCount, null, List.of());
        }
    }
}
