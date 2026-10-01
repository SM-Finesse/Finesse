package com.finesse.backend.calc.service;

import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.StatResult;

/**
 * StatCalculatorFacade.analyze()의 결과 (설계서 19.5절).
 * Cold Start와 수집 실패는 예외가 아니라 정상 분기로 돌려준다(3.3절). 백엔드는 switch로 분기한다.
 */
public sealed interface AnalysisOutcome
        permits AnalysisOutcome.Analyzed, AnalysisOutcome.ColdStartBypass,
        AnalysisOutcome.UserNotFound, AnalysisOutcome.CollectionFailed {

    /** 정상 분석 */
    record Analyzed(UserSummary summary, StatResult result, AnalysisMeta meta) implements AnalysisOutcome {}

    /** 분석 가능한 매치가 10판 미만 — 통계 계산을 하지 않는다 (3.3·5.8절) */
    record ColdStartBypass(UserSummary summary, int availableMatches, ColdStartReason reason) implements AnalysisOutcome {}

    /** 존재하지 않는 TETR.IO 유저 */
    record UserNotFound(String username) implements AnalysisOutcome {}

    /** TETR.IO 호출 실패로 분석할 수 없음 (3.7절: FAILED, INSUFFICIENT) */
    record CollectionFailed(CollectionStatus status) implements AnalysisOutcome {}

    enum ColdStartReason {
        FEW_GAMES_TOTAL,      // 누적 판수 10판 미만 (예비 판정)
        FEW_GAMES_IN_YEAR,    // 1년 이내 판수 10판 미만 (최종 판정)
        TOO_MANY_INVALID      // 비정상 매치 제외 후 10판 미만 (5.8절 — 신규 유저와 메시지만 구분)
    }
}