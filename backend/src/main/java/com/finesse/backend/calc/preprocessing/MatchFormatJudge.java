package com.finesse.backend.calc.preprocessing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 경기 형식(firstTo)·조기 종료(endedEarly) 판정 (설계서 5.11절, v3.5).
 * 형식은 매치 당시 등급 1차, 매치 당시 TR 2차로 선수마다 구하고, 본인·상대 형식을 모두 후보로 본다.
 * 승자 승수가 3·5·7이 아닌 매치는 MatchValidator에서 이미 INCOMPLETE_MATCH로 빠진다.
 */
final class MatchFormatJudge {

    static final double FT5_MIN_TR = 13_800.0;   // 이상이면 5선승
    static final double FT7_MIN_TR = 20_000.0;   // 이상이면 7선승

    /** @param firstTo 선승 수(3·5·7), 판단 불가면 null */
    record Judgment(Integer firstTo, boolean endedEarly) {}

    private MatchFormatJudge() {}

    /**
     * 선수 한 명의 경기 형식. 등급이 있으면 등급으로(D~A+ 3, S-~SS 5, U~X+ 7),
     * 등급이 없거나 순위권 밖(z)이면 TR로 정한다. 둘 다 없으면 null.
     */
    static Integer formatOf(String rank, Double tr) {
        Integer byRank = rank == null ? null : switch (rank.toLowerCase(Locale.ROOT)) {
            case "d", "d+", "c-", "c", "c+", "b-", "b", "b+", "a-", "a", "a+" -> 3;
            case "s-", "s", "s+", "ss" -> 5;
            case "u", "x", "x+" -> 7;
            default -> null;   // z(순위권 밖)·알 수 없는 값 → TR로
        };
        if (byRank != null) {
            return byRank;
        }
        if (tr == null) {
            return null;
        }
        if (tr >= FT7_MIN_TR) return 7;
        if (tr >= FT5_MIN_TR) return 5;
        return 3;
    }

    /**
     * 5.11절 판정 규칙 — 위에서부터 처음 맞는 행을 적용한다.
     *
     * @param winnerWins 승자 승수(3·5·7)
     */
    static Judgment judge(int winnerWins, Integer myFormat, Integer oppFormat) {
        List<Integer> known = new ArrayList<>(2);
        if (myFormat != null) known.add(myFormat);
        if (oppFormat != null) known.add(oppFormat);

        if (known.contains(winnerWins)) {
            return new Judgment(winnerWins, false);                          // 1
        }
        if (!known.isEmpty()) {
            int min = known.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int max = known.stream().mapToInt(Integer::intValue).max().orElseThrow();
            if (winnerWins < min) return new Judgment(min, true);            // 2
            if (winnerWins > max) return new Judgment(winnerWins, false);    // 3
            return new Judgment(null, false);                               // 4
        }
        if (winnerWins == 7) return new Judgment(7, false);                  // 5
        return new Judgment(null, false);                                   // 6
    }
}
