package com.finesse.backend.calc.collector;

/** 수집 결과 상태 (설계서 3.7절) */
public enum CollectionStatus {
    COMPLETE,      // 실패한 페이지 없음
    PARTIAL,       // 일부 페이지 실패, minPartialMatches 이상 확보
    INSUFFICIENT,  // 일부 페이지 실패, minPartialMatches 미만 확보
    FAILED,        // 성공한 페이지 없음
    RATE_LIMITED   // collect 브랜치: FAILED·INSUFFICIENT인데 그 원인이 429 — 학습 데이터 수집기가 rate_limited로 기록
}