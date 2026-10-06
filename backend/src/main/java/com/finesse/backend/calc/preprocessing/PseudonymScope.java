package com.finesse.backend.calc.preprocessing;

import com.finesse.backend.calc.domain.PseudonymId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 단일 분석 요청 범위의 상대 식별자 매핑 (설계서 5.5·5.6절).
 * 키는 상대 TETR.IO 유저 ID이며, 표시용으로 해당 상대와의 가장 최근 매치 당시 닉네임을 보관한다.
 * 원본 유저 ID·닉네임은 이 객체 밖으로 나가지 않는다 — 외부에는 마스킹 닉네임만 반환한다.
 * 요청마다 새로 만들고 요청이 끝나면 버린다(스레드 간 공유 금지).
 */
public final class PseudonymScope {

    private final Map<String, PseudonymId> userIdToPseudonym = new HashMap<>();
    private final Map<PseudonymId, String> latestNicknameAtMatch = new HashMap<>();
    private int sequence = 0;

    PseudonymScope() {}

    /**
     * 매치는 최신순으로 전달된다(3.4절). 처음 본 상대에게 PseudonymId를 발급하고,
     * 그때의 닉네임(= 가장 최근 매치 당시 닉네임)을 표시용으로 보관한다.
     */
    public PseudonymId resolve(String opponentUserId, String nicknameAtMatch) {
        Objects.requireNonNull(opponentUserId, "opponentUserId");
        Objects.requireNonNull(nicknameAtMatch, "nicknameAtMatch");
        PseudonymId id = userIdToPseudonym.computeIfAbsent(
                opponentUserId, key -> PseudonymId.of(sequence++));
        latestNicknameAtMatch.putIfAbsent(id, nicknameAtMatch);
        return id;
    }

    /** 마스킹된 표시용 닉네임만 반환한다 (5.9부절, 11.12절). */
    public String resolveMaskedNickname(PseudonymId id, NicknamePseudonymizer masker) {
        String nickname = latestNicknameAtMatch.get(id);
        if (nickname == null) {
            throw new IllegalArgumentException("이 스코프에서 발급되지 않은 PseudonymId: " + id.value());
        }
        return masker.pseudonymize(nickname);
    }

    public int size() {
        return userIdToPseudonym.size();
    }
}