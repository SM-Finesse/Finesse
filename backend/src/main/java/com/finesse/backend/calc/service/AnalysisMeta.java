package com.finesse.backend.calc.service;

/**
 * 분석에 쓰인 데이터 범위 (설계서 3.7·5.8절).
 *
 * @param analyzedMatches  정제 후 분석에 쓴 현재 구간 매치 수
 * @param previousMatches  구간 비교(11.11절)에 쓴 이전 구간 매치 수
 * @param partial          일부 페이지 수집 실패 — "최근 300판 중 일부만 반영" 안내용
 * @param excludedMatches  정제 단계에서 제외한 매치 수
 * @param droppedRecords   응답 구조가 예상과 달라 버린 레코드 수
 */
public record AnalysisMeta(
        int analyzedMatches,
        int previousMatches,
        boolean partial,
        int excludedMatches,
        int droppedRecords
) {}