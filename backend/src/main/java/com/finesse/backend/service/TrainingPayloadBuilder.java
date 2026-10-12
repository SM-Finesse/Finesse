package com.finesse.backend.service;

import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.finesse.backend.dto.LlmHeavyChapterRequest;
import com.finesse.backend.dto.LlmLightRequest;
import com.finesse.backend.dto.StatsResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 학습 데이터 수집(collector 프로파일 전용) — 서비스가 LLM에 보내려고 조립하는 요청을 그대로 만든다.
 * 재조립하지 않는다: light는 CommentService.getLight와 같은 LlmLightRequest.from(stats),
 * heavy는 CommentService.getHeavyStream과 같은 buildChapterData(stats) → LlmHeavyChapterRequest(chapterId, data).
 * LLM은 부르지 않는다(학습 데이터 수집 서버 작업지시서 3.2절).
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
        return new Payloads(LlmLightRequest.from(stats), heavy, stats.fixedMetrics().recentForm(), stats.matchCount());
    }

    /**
     * @param recentForm light.csv recent_form_json 열(참고용) — LLM light 요청(v1.2)에는 들어가지 않는 값이라 따로 둔다
     * @param matchCount 정제 후 유효 매치 수
     */
    public record Payloads(LlmLightRequest light, Map<String, LlmHeavyChapterRequest> heavy,
                           List<String> recentForm, int matchCount) {
    }
}
