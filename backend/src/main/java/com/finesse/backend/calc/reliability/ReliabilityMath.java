package com.finesse.backend.calc.reliability;

import java.util.Arrays;
import java.util.BitSet;

/**
 * 신뢰도 판정 공식 (「지표 신뢰도 판정 기준」 2장). 모두 통계학 표준 공식이다.
 * 외부 라이브러리 없이 계산한다 — t 분포 분위수는 정칙화 불완전 베타 함수를 이분법으로 풀어 구한다.
 */
final class ReliabilityMath {

    /** 신뢰수준 95%의 표준정규 임계값 */
    static final double Z95 = 1.959963984540054;

    /** df 1~399의 97.5% 분위수를 미리 계산해 둔다 (분석 매치는 최대 300판) */
    private static final int T_CACHE = 400;
    private static final double[] T975 = new double[T_CACHE];

    static {
        for (int df = 1; df < T_CACHE; df++) {
            T975[df] = tQuantileTwoSided(df, 0.05);
        }
    }

    private ReliabilityMath() {}

    // ── 기술 통계 ────────────────────────────────────────────────────

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    /** 표본표준편차 (n − 1). 값이 2개 미만이면 NaN */
    static double sampleSd(double[] x) {
        if (x.length < 2) {
            return Double.NaN;
        }
        double m = mean(x);
        double ss = 0;
        for (double v : x) {
            ss += (v - m) * (v - m);
        }
        return Math.sqrt(ss / (x.length - 1));
    }

    static double median(double[] x) {
        double[] s = x.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    // ── t 분포 (2.2절) ───────────────────────────────────────────────

    /** 자유도 df인 t 분포의 97.5% 분위수 (95% 양측 임계값). df = 9 → 2.262, 29 → 2.045, 99 → 1.984 */
    static double t975(int df) {
        if (df < 1) {
            throw new IllegalArgumentException("자유도는 1 이상이어야 합니다: " + df);
        }
        return df < T_CACHE ? T975[df] : tQuantileTwoSided(df, 0.05);
    }

    /** P(|T| > t) = alpha가 되는 t. P(|T| > t) = I_{df/(df+t²)}(df/2, 1/2) */
    static double tQuantileTwoSided(int df, double alpha) {
        double lo = 0;
        double hi = 1;
        while (twoSidedTail(df, hi) > alpha) {
            hi *= 2;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (twoSidedTail(df, mid) > alpha) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    private static double twoSidedTail(int df, double t) {
        return regularizedBeta(df / (df + t * t), df / 2.0, 0.5);
    }

    /** 정칙화 불완전 베타 함수 I_x(a, b) — 연분수 전개 (Numerical Recipes 6.4절) */
    static double regularizedBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double bt = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return bt * betaContinuedFraction(x, a, b) / a;
        }
        return 1 - bt * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        final double tiny = 1e-300;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 1000; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) {
                break;
            }
        }
        return h;
    }

    /** ln Γ(x) — Lanczos 근사 (g = 7, 9항) */
    static double logGamma(double x) {
        final double[] coef = {
                0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012,
                9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        x -= 1;
        double a = coef[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            a += coef[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    // ── Wilson·Newcombe 구간 (2.4·2.5절) ─────────────────────────────

    /** 비율 k/n의 Wilson 구간 [하한, 상한]. n = 0이면 [0, 1] */
    static double[] wilson(int k, int n, double z) {
        if (n <= 0) {
            return new double[] {0, 1};
        }
        double p = (double) k / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = z / denom * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        // k = 0 · k = n이면 한쪽 끝은 수식상 정확히 0 · 1이다 — 반올림 오차를 남기지 않는다
        double lo = k == 0 ? 0 : Math.max(0, center - half);
        double hi = k == n ? 1 : Math.min(1, center + half);
        return new double[] {lo, hi};
    }

    /** 두 비율 차이 d = ka/na − kb/nb의 Newcombe 구간 [L, U] (Newcombe 1998, 방법 10) */
    static double[] newcombe(int ka, int na, int kb, int nb, double z) {
        double pa = (double) ka / na;
        double pb = (double) kb / nb;
        double[] a = wilson(ka, na, z);
        double[] b = wilson(kb, nb, z);
        double d = pa - pb;
        double lower = d - Math.sqrt(sq(pa - a[0]) + sq(b[1] - pb));
        double upper = d + Math.sqrt(sq(a[1] - pa) + sq(pb - b[0]));
        return new double[] {lower, upper};
    }

    private static double sq(double v) {
        return v * v;
    }

    // ── 이상치: 수정 Z-점수 (2.7절) ─────────────────────────────────

    /**
     * |M_i| > threshold인 인덱스. M_i = 0.6745 (x_i − 중앙값) / MAD.
     * MAD = 0이면 이 값으로는 이상치를 찾지 않는다 — null을 돌려준다.
     */
    static BitSet modifiedZOutliers(double[] x, double threshold) {
        if (x.length == 0) {
            return null;
        }
        double med = median(x);
        double[] dev = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            dev[i] = Math.abs(x[i] - med);
        }
        double mad = median(dev);
        if (mad == 0.0) {
            return null;
        }
        BitSet out = new BitSet(x.length);
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(0.6745 * (x[i] - med) / mad) > threshold) {
                out.set(i);
            }
        }
        return out;
    }
}