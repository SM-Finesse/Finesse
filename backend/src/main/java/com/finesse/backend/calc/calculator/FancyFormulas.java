package com.finesse.backend.calc.calculator;

/**
 * 매치 1판의 APM·PPS·VS로 계산하는 파생 지표 공식 (설계서 6장).
 * 본인 지표(FancyMathCalculator)와 본인 − 상대 Δ(DeltaStatCalculator)가 같은 공식을 쓰도록 한곳에 둔다.
 * Calculator끼리 서로 의존하지 않기 위한 공용 유틸이다(ArchitectureTest).
 */
final class FancyFormulas {

    static final double MIN_PPS = 0.1;
    static final double DIVERGENCE_EPSILON = 1e-6;

    private FancyFormulas() {
    }

    /** APP = apm / (pps × 60) */
    static double app(double apm, double pps) {
        return apm / (pps * 60.0);
    }

    static double vsApm(double vs, double apm) {
        return vs / apm;
    }

    static double dsS(double vs, double apm) {
        return (vs / 100.0) - (apm / 60.0);
    }

    static double dsP(double dsS, double pps) {
        return dsS / pps;
    }

    static double cheeseIndex(double dsP, double vsApm, double app) {
        return (dsP * 150.0) + ((vsApm - 2.0) * 50.0) + (0.6 - app) * 125.0;
    }

    static double gbE(double app, double dsS, double pps) {
        return ((app * dsS) / pps) * 2.0;
    }

    /** tan 입력이 ±90°에 가까워 발산하면 APP 원값으로 대체한다 (설계서 6.4절). */
    static double weightedApp(double app, double cheese) {
        double radians = Math.toRadians((cheese / -30.0) + 1.0);
        if (Math.abs(Math.cos(radians)) < DIVERGENCE_EPSILON) {
            return app;
        }
        return app - 5.0 * Math.tan(radians);
    }

    // ── 원값(APM·PPS·VS)에서 바로 구하는 편의 메서드 ─────────────────

    static double cheeseIndexOf(double apm, double pps, double vs) {
        return cheeseIndex(dsP(dsS(vs, apm), pps), vsApm(vs, apm), app(apm, pps));
    }

    static double weightedAppOf(double apm, double pps, double vs) {
        return weightedApp(app(apm, pps), cheeseIndexOf(apm, pps, vs));
    }

    // ── 플레이스타일 (설계서 8장) ─────────────────────────────────
    // 커뮤니티 공개 계산식을 옮긴 것이며 TETR.IO 공식 지표가 아니다.
    // 출처: https://github.com/dan63047/TetraStats/wiki/Meaning-and-the-essence-of-stats
    //       (정규화 계수 nm*는 같은 프로젝트의 lib/data_objects/playstyle.dart)
    // srArea·statrank는 플레이스타일 정규화에만 쓰는 중간값이며, 이 값으로 TR을 추정하지 않는다(13.1절).

    /** 플레이스타일 4개 원값. 0.5 근처가 같은 statrank 기준 평균 수준이다. */
    record Playstyle(double opener, double plonk, double stride, double infDs) {}

    static double srArea(double pps, double app, double dsP) {
        return pps * 135.0 + app * 290.0 + dsP * 700.0;
    }

    static double statrank(double srArea) {
        double rank = 11.2 * Math.atan((srArea - 93.0) / 130.0) + 1.0;
        return rank <= 0.0 ? 0.001 : rank;
    }

    /** APM > 0, PPS > 0인 매치 1판의 플레이스타일. srArea ≤ 0이거나 결과가 유한하지 않으면 null(계산 불가). */
    static Playstyle playstyleOf(double apm, double pps, double vs) {
        double app = app(apm, pps);
        double vsApm = vsApm(vs, apm);
        double dsS = dsS(vs, apm);
        double dsP = dsP(dsS, pps);
        double gbE = gbE(app, dsS, pps);
        double area = srArea(pps, app, dsP);
        if (!(area > 0.0)) {
            return null;
        }
        double sr = statrank(area);

        double nmApm = ((apm / area) / ((0.069 * Math.pow(1.0017, Math.pow(sr, 5) / 4700.0)) + sr / 360.0)) - 1.0;
        double nmPps = ((pps / area) / (0.0084264 * Math.pow(2.14, -2.0 * (sr / 2.7 + 1.03)) - sr / 5750.0 + 0.0067)) - 1.0;
        double nmApp = (app / (0.1368803292 * Math.pow(1.0024, Math.pow(sr, 5) / 2800.0) + sr / 54.0)) - 1.0;
        double nmDsP = (dsP / (0.02136327583 * Math.pow(14.0, (sr - 14.75) / 3.9) + sr / 152.0 + 0.022)) - 1.0;
        double nmGbE = (gbE / (sr / 350.0 + 0.005948424455 * Math.pow(3.8, (sr - 6.1) / 4.0) + 0.006)) - 1.0;
        double nmVsApm = (vsApm / (-Math.pow((sr - 16.0) / 36.0, 2) + 2.133)) - 1.0;

        double opener = ((nmApm + nmPps * 0.75 + nmVsApm * -10.0 + nmApp * 0.75 + nmDsP * -0.25) / 3.5) + 0.5;
        double plonk = ((nmGbE + nmApp + nmDsP * 0.75 + nmPps * -1.0) / 2.73) + 0.5;
        double stride = ((nmApm * -0.25 + nmPps + nmApp * -2.0 + nmDsP * -0.5) * 0.79) + 0.5;
        double infDs = ((nmDsP + nmApp * -0.75 + nmApm * 0.5 + nmVsApm * 1.5 + nmPps * 0.5) * 0.9) + 0.5;

        if (!Double.isFinite(opener) || !Double.isFinite(plonk)
                || !Double.isFinite(stride) || !Double.isFinite(infDs)) {
            return null;
        }
        return new Playstyle(opener, plonk, stride, infDs);
    }
}