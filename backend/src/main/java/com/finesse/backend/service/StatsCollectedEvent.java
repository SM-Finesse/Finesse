package com.finesse.backend.service;

import com.finesse.backend.dto.StatsResponse;

/**
 * 대기열로 수집한 stats가 캐시에 들어감 — CommentService가 받아 라이트 코멘트를 같은 순서로 미리 만든다
 * (「전적 검색 대기열 문서」 4.1절). StatsService가 CommentService를 직접 부르면 서로를 의존하게 돼 이벤트로 잇는다.
 *
 * @param ticketed 대기표로 수집한 유저인지 — 미리 만들기는 이때만(요청 안에서 끝난 사람은 이어서 light를 부를 때 입장 판정)
 */
public record StatsCollectedEvent(String username, StatsResponse stats, boolean ticketed) {
}
