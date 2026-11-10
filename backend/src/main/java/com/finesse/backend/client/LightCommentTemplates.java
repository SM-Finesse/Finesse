package com.finesse.backend.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 라이트 개별·기본 코멘트 대체 문장 틀 — LLM 서버 두 대가 모두 실패했을 때 백엔드가 쓴다
 * (LLM 연동 명세 light-v5.6d 부록 A1·A2, 10/27). LLM 서버의 대체 문장(llm-server app/light_comment.py
 * state·direction·build_sentence_text·build_summary_text)과 같은 규칙으로 만든다.
 */
public final class LightCommentTemplates {

    private LightCommentTemplates() {
    }

    /** 31번 1.3절 비교 표현 5개 — 값이 0이면 "최근 대전 상대들보다"는 쓰지 않는다 */
    static final List<String> COMPARE_PHRASES =
            List.of("상대 대비", "맞붙은 상대들에 비해", "최근 대전 상대들보다", "대전 상대 평균 대비", "매 경기 상대와 견줘 보면");
    private static final List<String> ZERO_PHRASES =
            COMPARE_PHRASES.stream().filter(p -> !p.endsWith("보다")).toList();

    /** 31번 1.8절 지표 이름 (PM 확정 10/26, Infinite Downstack은 P1 규칙 10/27) — 상대와 비교하는 8개 */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("delta_opener", "Opener 성향"),
            Map.entry("delta_plonk", "Plonk 성향"),
            Map.entry("delta_stride", "Stride 성향"),
            Map.entry("delta_inf_ds", "Infinite Downstack 성향"),
            Map.entry("delta_app", "블록당 공격력"),
            Map.entry("delta_weighted_app", "보정 공격 효율"),
            Map.entry("delta_vs_apm", "수비 비중"),
            Map.entry("delta_cheese_index", "가비지 처리 성향"));
    private static final Set<String> RANK_KEYS = Set.of("delta_app", "delta_weighted_app");

    /** 기본 코멘트 첫머리 틀 (P1 규칙 6절) */
    static final List<String> SUMMARY_OPENERS = List.of("이번 분석에서 눈에 띄는 점은", "대전 기록에서 두드러지는 부분은");

    /** 끝말 → {가운데 지표 '~고' 꼴, 첫 지표 '~다는' 꼴} (부록 A2 끝말 바꾸기 표) */
    private static final Map<String, String[]> ENDINGS = new LinkedHashMap<>();

    static {
        ENDINGS.put("앞섭니다", new String[]{"앞서고", "앞선다는"});
        ENDINGS.put("밀립니다", new String[]{"밀리고", "밀린다는"});
        ENDINGS.put("짙습니다", new String[]{"짙고", "짙다는"});
        ENDINGS.put("옅습니다", new String[]{"옅고", "옅다는"});
        ENDINGS.put("강합니다", new String[]{"강하고", "강하다는"});
        ENDINGS.put("오릅니다", new String[]{"오르고", "오른다는"});
        ENDINGS.put("처집니다", new String[]{"처지고", "처진다는"});
        ENDINGS.put("떨어집니다", new String[]{"떨어지고", "떨어진다는"});
        ENDINGS.put("높습니다", new String[]{"높고", "높다는"});
        ENDINGS.put("않습니다", new String[]{"않고", "않는다는"});
        ENDINGS.put("없습니다", new String[]{"없고", "없다는"});
        ENDINGS.put("편입니다", new String[]{"편이고", "편이라는"});
        ENDINGS.put("흐름입니다", new String[]{"흐름이고", "흐름이라는"});
        ENDINGS.put("수준입니다", new String[]{"수준이고", "수준이라는"});
    }

    /** 상대와 비교하는 지표 — 문장 앞에 비교 표현을 붙인다(역전 경향은 경향으로만 쓰므로 뺌) */
    static boolean comparesWithOpponent(String stat) {
        return NAMES.containsKey(stat);
    }

    /** 개별 코멘트 대체 문장(부록 A1). 값은 끝에 괄호로 붙인다 — 예: "상대 대비 블록당 공격력에서 뚜렷하게 앞섭니다(+0.08)." */
    public static String sentence(String stat, double value, String zone, Random rng) {
        return body(stat, value, zone, rng) + "(" + formatValue(stat, value) + ").";
    }

    /**
     * 기본 코멘트 대체 문장(부록 A2) — 숫자 없이, 첫 지표는 "이번 분석에서 눈에 띄는 점은 …다는 것입니다.",
     * 나머지는 "~고"로 이어 한 문장으로 쓴다. 비교 표현은 지표마다 따로 고른다.
     */
    public static String summary(List<String> stats, List<Double> values, List<String> zones, Random rng) {
        List<String[]> parts = new ArrayList<>();
        for (int i = 0; i < stats.size(); i++) {
            parts.add(clause(body(stats.get(i), values.get(i), zones.get(i), rng)));
        }
        String opener = SUMMARY_OPENERS.get(rng.nextInt(SUMMARY_OPENERS.size()));
        StringBuilder sb = new StringBuilder(opener).append(' ')
                .append(parts.get(0)[0]).append(ENDINGS.get(parts.get(0)[1])[1]).append(" 것입니다.");
        if (parts.size() > 1) {
            sb.append(' ');
            for (int i = 1; i < parts.size() - 1; i++) {
                sb.append(parts.get(i)[0]).append(ENDINGS.get(parts.get(i)[1])[0]).append(", ");
            }
            String[] last = parts.get(parts.size() - 1);
            sb.append(last[0]).append(last[1]).append('.');
        }
        return sb.toString();
    }

    /** 비교 표현 + 방향 문장, 값·마침표 없음 */
    private static String body(String stat, double value, String zone, Random rng) {
        String st = state(stat, value, zone);
        String body = direction(stat, st);
        if (comparesWithOpponent(stat)) {
            List<String> phrases = "zero".equals(st) ? ZERO_PHRASES : COMPARE_PHRASES;
            body = phrases.get(rng.nextInt(phrases.size())) + " " + body;
        }
        return body;
    }

    /** {끝말 앞 본문, 끝말} */
    private static String[] clause(String body) {
        for (String end : ENDINGS.keySet()) {
            if (body.endsWith(end)) {
                return new String[]{body.substring(0, body.length() - end.length()), end};
            }
        }
        throw new IllegalStateException("끝말 표에 없음: " + body);
    }

    /**
     * P1 규칙 1절 — 소수 둘째 자리, 가비지 처리 성향은 첫째 자리, delta_comeback은 원값.
     * LLM 서버(파이썬 round)와 같은 결과가 나오게 HALF_EVEN
     */
    static BigDecimal rounded(String stat, double value) {
        if ("delta_comeback".equals(stat)) {
            return new BigDecimal(Double.toString(value));
        }
        return new BigDecimal(value).setScale("delta_cheese_index".equals(stat) ? 1 : 2, RoundingMode.HALF_EVEN);
    }

    static String formatValue(String stat, double value) {
        BigDecimal r = rounded(stat, value);
        if (r.signum() == 0) {
            return "delta_comeback".equals(stat) ? "0" : "delta_cheese_index".equals(stat) ? "0.0" : "0.00";
        }
        return (r.signum() > 0 ? "+" : "") + r.toPlainString();
    }

    /**
     * 문장 상태 — 31번 2.2절 A안(10/26 확정): 방향은 값의 부호, 강도는 영역.
     * high·low 같은 쪽(양수·높음 / 음수·낮음), weak+·weak- 반대쪽(양수·낮음 / 음수·높음), mid+·mid- 보통, zero 값 0
     */
    static String state(String stat, double value, String zone) {
        int sign = rounded(stat, value).signum();
        if (sign == 0) {
            return "zero";
        }
        String s = sign > 0 ? "+" : "-";
        if ("보통".equals(zone)) {
            return "mid" + s;
        }
        if (("높음".equals(zone) && sign > 0) || ("낮음".equals(zone) && sign < 0)) {
            return sign > 0 ? "high" : "low";
        }
        return "weak" + s;
    }

    static String direction(String stat, String st) {
        String name = NAMES.get(stat);
        if (RANK_KEYS.contains(stat)) {
            return switch (st) {
                case "high" -> name + "에서 뚜렷하게 앞섭니다";
                case "low" -> name + "에서 뚜렷하게 밀립니다";
                case "weak+" -> name + "에서 조금 앞서지만 그 폭은 작은 편입니다";
                case "weak-" -> name + "에서 조금 밀리지만 그 폭은 작은 편입니다";
                case "mid+" -> name + "에서 앞서는 편입니다";
                case "mid-" -> name + "에서 밀리는 편입니다";
                default -> name + topic(name) + " 같은 수준입니다";
            };
        }
        if ("delta_vs_apm".equals(stat)) {
            String s = subject(name);
            return switch (st) {
                case "high" -> name + s + " 뚜렷하게 큰 편입니다";
                case "low" -> name + s + " 뚜렷하게 작은 편입니다";
                case "weak+" -> name + s + " 조금 큰 편이지만 그 폭은 크지 않습니다";
                case "weak-" -> name + s + " 조금 작은 편이지만 그 폭은 크지 않습니다";
                case "mid+" -> name + s + " 큰 편입니다";
                case "mid-" -> name + s + " 작은 편입니다";
                default -> name + topic(name) + " 같은 수준입니다";
            };
        }
        if (name != null) { // 성향 지표 — Opener·Plonk·Stride·Infinite Downstack·가비지 처리
            String s = subject(name);
            return switch (st) {
                case "high" -> name + s + " 뚜렷하게 짙습니다";
                case "low" -> name + s + " 뚜렷하게 옅습니다";
                case "weak+" -> name + s + " 조금 짙지만 그 폭은 작은 편입니다";
                case "weak-" -> name + s + " 조금 옅지만 그 폭은 작은 편입니다";
                case "mid+" -> name + s + " 짙은 편입니다";
                case "mid-" -> name + s + " 옅은 편입니다";
                default -> name + topic(name) + " 같은 수준입니다";
            };
        }
        if ("strength_split".equals(stat)) {
            String head = "강한 상대 구간에서는 약한 상대 구간보다 승률이";
            return switch (st) {
                case "high" -> head + " 오히려 높습니다";
                case "low" -> head + " 크게 떨어집니다";
                case "weak+" -> head + " 오히려 조금 높지만 그 폭은 작은 편입니다";
                case "weak-" -> "강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편입니다";
                case "mid+" -> head + " 오히려 높은 편입니다";
                case "mid-" -> head + " 떨어지는 편입니다";
                default -> "강한 상대 구간에서는 약한 상대 구간과 승률이 같은 수준입니다";
            };
        }
        if ("delta_comeback".equals(stat)) {
            String win = "역전승 경향이 역전패 경향보다";
            String lose = "역전패 경향이 역전승 경향보다";
            return switch (st) {
                case "high" -> win + " 뚜렷하게 강합니다";
                case "low" -> lose + " 뚜렷하게 강합니다";
                case "weak+" -> win + " 조금 강하지만 그 폭은 작은 편입니다";
                case "weak-" -> lose + " 조금 강하지만 그 폭은 작은 편입니다";
                case "mid+" -> win + " 강한 편입니다";
                case "mid-" -> lose + " 강한 편입니다";
                default -> "역전승 경향과 역전패 경향이 같은 수준입니다";
            };
        }
        String head = "라운드가 진행될수록 공방 능력이"; // session_vs_slope
        return switch (st) {
            case "high" -> head + " 뚜렷하게 오릅니다";
            case "low" -> head + " 뚜렷하게 처집니다";
            case "weak+" -> head + " 오르기는 하지만 그 폭은 작은 편입니다";
            case "weak-" -> head + " 처지기는 하지만 그 폭은 작은 편입니다";
            case "mid+" -> head + " 오르는 흐름입니다";
            case "mid-" -> head + " 처지는 흐름입니다";
            default -> "라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다";
        };
    }

    /** 은/는 — 괄호 앞 한글 글자의 받침으로 고른다. 한글이 아니면 '는' (LLM 서버 fallback._topic과 같음) */
    static String topic(String name) {
        return hasBatchim(name) == null ? "는" : (hasBatchim(name) ? "은" : "는");
    }

    /** 이/가 */
    static String subject(String name) {
        return hasBatchim(name) == null ? "가" : (hasBatchim(name) ? "이" : "가");
    }

    private static Boolean hasBatchim(String name) {
        String word = name.split("\\(")[0].strip();
        if (word.isEmpty()) {
            return null;
        }
        char last = word.charAt(word.length() - 1);
        if (last < '가' || last > '힣') {
            return null;
        }
        return (last - '가') % 28 != 0;
    }
}
