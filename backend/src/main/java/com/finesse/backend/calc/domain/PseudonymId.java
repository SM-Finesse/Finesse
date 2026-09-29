package com.finesse.backend.calc.domain;

public record PseudonymId(String value) {

    public static PseudonymId of(int sequence) {
        return new PseudonymId("User_" + toAlpha(sequence));
    }

    private static String toAlpha(int sequence) {
        StringBuilder sb = new StringBuilder();
        int n = sequence;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        } while (n >= 0);
        return sb.toString();
    }
}