package com.finesse.backend.calc.service;

/**
 * 분석에 쓰인 데이터 범위 (설계서 3.7·5.8절).
 *
 * @param analyzedMatches  정제 후 분석에 쓴 현재 구간 매치 수
 * @param previousMatches  사용하지 않음 — 항상 0. v3.10에서 이전 구간 수집을 없앴고, 백엔드 참조가 지워지면 삭제한다 (P2)
 * @param partial          일부 페이지 수집 실패 — "최근 300판 중 일부만 반영" 안내용
 * @param excludedMatches  정제 단계에서 제외한 매치 수
 * @param droppedRecords   응답 구조가 예상과 달라 버린 레코드 수
 * @param endedEarlyMatches    분석 매치 중 조기 종료(중간 이탈 추정) 판수 (5.11절)
 * @param formatUnknownMatches 분석 매치 중 경기 형식 판단 불가 판수 (5.11절)
 */
public record AnalysisMeta(
        int analyzedMatches,
        @Deprecated int previousMatches,
        boolean partial,
        int excludedMatches,
        int droppedRecords,
        int endedEarlyMatches,
        int formatUnknownMatches
) {}