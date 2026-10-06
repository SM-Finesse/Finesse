package com.finesse.backend.calc.preprocessing;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 상대방 닉네임 표시용 마스킹 (설계서 5.3절).
 * mask_count = max(1, floor(length / 3)), start_index = floor((length - mask_count) / 2) — 가운데를 마스킹한다.
 * 결과는 표시 전용이며 그룹핑 근거로 쓰지 않는다(5.5절).
 */
@Component
public class NicknamePseudonymizer {

    static final char MASK_CHAR = '*';

    public String pseudonymize(String nickname) {
        Objects.requireNonNull(nickname, "nickname");
        int length = nickname.length();
        if (length == 0) {
            return nickname;
        }
        int maskCount = Math.max(1, length / 3);
        int startIndex = (length - maskCount) / 2;
        return applyMask(nickname, startIndex, maskCount);
    }

    private String applyMask(String nickname, int startIndex, int maskCount) {
        StringBuilder sb = new StringBuilder(nickname);
        for (int i = startIndex; i < startIndex + maskCount; i++) {
            sb.setCharAt(i, MASK_CHAR);
        }
        return sb.toString();
    }
}