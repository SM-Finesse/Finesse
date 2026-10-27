package com.finesse.backend.dto;

import java.util.List;

/**
 * 백엔드 ↔ LLM 서버 라이트 개별·기본 코멘트 (LLM 연동 명세 light-v5.2, 10/21).
 * LLM 서버는 모르는 필드가 오면 422로 거절하므로 요청에는 {stat, value, zone} 세 개만 담는다.
 */
public final class LlmLightComment {

    private LlmLightComment() {
    }

    /**
     * 지표 하나 — 개별 코멘트 요청 본문이자 기본 코멘트의 items 원소.
     *
     * @param stat  후보 11개 키
     * @param value calc 원값 그대로(반올림은 LLM 서버가 함). delta_comeback도 화면과 같은 원값
     * @param zone  calc 판정 영역 "높음"·"보통"·"낮음" — 항상 보낸다("없음"이면 422라 선정에서 이미 빠짐)
     */
    public record Item(String stat, double value, String zone) {
    }

    public record SummaryRequest(List<Item> items) {
    }

    /** fallback: LLM 문장이 검사를 통과하지 못하거나 시간이 모자라 서버가 대체 문장을 썼으면 true */
    public record SentenceResponse(String stat, String sentence, Boolean fallback, String version) {
    }

    public record SummaryResponse(String lightSummary, Boolean fallback, String version) {
    }
}
