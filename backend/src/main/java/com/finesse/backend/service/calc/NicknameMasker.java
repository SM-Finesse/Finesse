package com.finesse.backend.service.calc;

/**
 * FR-09 상대방 닉네임 마스킹 (강제 적용) — mask_count = max(1, floor(len/3)),
 * start_index = floor((len - mask_count) / 2), 중앙 마스킹.
 *
 * ※ 임시 구현 — 데이터팀(위성훈, data-eng 브랜치)의 PseudonymId(com.finesse.backend.calc.domain,
 * User_A/User_B 방식)로 교체될 수 있음 (2026-09-29 팀 확인, 방식이 달라 최종 채택안은 협의 필요).
 */
public final class NicknameMasker {
    private NicknameMasker() {
    }

    public static String mask(String nickname) {
        int len = nickname.length();
        int maskCount = Math.max(1, len / 3);
        int startIndex = (len - maskCount) / 2;
        StringBuilder sb = new StringBuilder(nickname);
        for (int i = startIndex; i < startIndex + maskCount && i < len; i++) {
            sb.setCharAt(i, '*');
        }
        return sb.toString();
    }
}
