package com.finesse.backend.service.calc;

/**
 * 데이터 명세서 v5 2절 "계산 파생 지표" — apm/pps/vs 원값만으로 계산, statrank 불필요.
 */
final class FancyMath {
    private FancyMath() {
    }

    static double app(double apm, double pps) {
        return apm / (pps * 60);
    }

    static double vsApm(double vs, double apm) {
        return vs / apm;
    }

    static double dsS(double vs, double apm) {
        return (vs / 100) - (apm / 60);
    }

    static double dsP(double dsS, double pps) {
        return dsS / pps;
    }

    static double cheeseIndex(double dsP, double vsApm, double app) {
        return (dsP * 150) + ((vsApm - 2) * 50) + (0.6 - app) * 125;
    }

    static double weightedApp(double app, double cheeseIndex) {
        return app - 5 * Math.tan(Math.toRadians((cheeseIndex / -30) + 1));
    }
}
