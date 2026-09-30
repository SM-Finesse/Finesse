package com.finesse.backend.calc.domain;

/** 반복 조우 상대 배지 (설계서 11.12절) */
public enum RivalBadge {
    NEMESIS,   // 천적: 승률 ≤ 40%
    DOMINANT,  // 우세: 승률 ≥ 65%
    CLOSE,     // 접전: 승률 45~55%
    NONE
}