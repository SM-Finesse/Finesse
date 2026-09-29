package com.finesse.backend.service.calc;

/**
 * FR-09 상대방 닉네임 마스킹 (강제 적용) — mask_count = max(1, floor(len/3)),
 * start_index = floor((len - mask_count) / 2), 중앙 마스킹.
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
