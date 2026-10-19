package com.finesse.backend.dto;

import java.util.List;

/**
 * GET /api/v1/comment/{username}?scope=light 응답.
 * 기능 명세서 3.5절 확정 스키마.
 *
 * @param attemptCount 사용한 재요청 횟수(0~3) — heavy 챕터의 attempt_count와 같은 기준(최초 시도로 바로 성공하면 0,
 *                     LLM 연결 실패는 세지 않음). QA가 응답만으로 재시도를 확인하게 한다(QA 검수요청서 R-01, 10/12).
 *                     LLM을 부르지 않은 응답(콜드스타트)은 생략, 캐시에서 꺼낸 응답은 처음 만들 때의 값.
 *                     LLM 서버 응답을 받을 때도 이 타입을 쓰지만 서버는 이 필드를 보내지 않는다(null).
 */
public record LightCommentResponse(String lightSummary, List<Highlight> highlights, Integer attemptCount) {

    public LightCommentResponse(String lightSummary, List<Highlight> highlights) {
        this(lightSummary, highlights, null);
    }

    public record Highlight(String stat, String sentence) {
    }
}
