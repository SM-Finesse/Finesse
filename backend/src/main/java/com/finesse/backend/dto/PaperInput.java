package com.finesse.backend.dto;

import java.util.List;
import java.util.Map;

/**
 * POST /api/paper/inputs 응답 원소(유저 1명) — 논문 실험 P0·P1 공통 입력 스냅샷.
 * 실패한 유저는 username·error만 담고 나머지는 생략된다(non_null).
 *
 * @param lightPicks    calc 선정 결과 → LLM 요청 형태 {stat, value, zone}, 배열 순서가 pick1 → pick3 (서비스 lightCommentItems와 같은 변환)
 * @param heavyChapters 서비스 buildChapterData 결과에서 rivals를 뺀 7챕터 (chapter_id → data)
 * @param calcCommit    계산에 쓴 calc 코드 git 커밋 해시
 * @param rangeVersion  보통 범위(Q1·Q3) 설정 버전 — calc highlight_judgement.ranges_version
 */
public record PaperInput(
        String username,
        String userId,
        String computedAt,
        Integer matchCount,
        List<LlmLightComment.Item> lightPicks,
        Map<String, Object> heavyChapters,
        String calcCommit,
        String rangeVersion,
        ApiErrorResponse error
) {
    public static PaperInput failed(String username, String errorCode, String message) {
        return new PaperInput(username, null, null, null, null, null, null, null,
                new ApiErrorResponse(errorCode, message));
    }
}
