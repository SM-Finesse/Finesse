package com.finesse.backend.calc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 분석 설정 (설계서 18장).
 * trTrendRatio는 TR Trend의 N = clamp(ceil(TR 있는 판수 × trTrendRatio), 3, 30) 계산에 쓴다 (11.1절).
 * 허용 범위는 0.1 ~ 0.5이며, 설정하지 않으면 0.3이다.
 */
@ConfigurationProperties(prefix = "finesse.analytics")
public record AnalyticsProperties(
        int coldStartThreshold,
        int maxMatchWindow,
        int recentWinLossWindow,
        Double trTrendRatio
) {
    public static final double DEFAULT_TR_TREND_RATIO = 0.3;
    public static final double MIN_TR_TREND_RATIO = 0.1;
    public static final double MAX_TR_TREND_RATIO = 0.5;

    @ConstructorBinding
    public AnalyticsProperties {
        if (trTrendRatio == null) {
            trTrendRatio = DEFAULT_TR_TREND_RATIO;
        }
        if (trTrendRatio < MIN_TR_TREND_RATIO || trTrendRatio > MAX_TR_TREND_RATIO) {
            throw new IllegalArgumentException(
                    "finesse.analytics.tr-trend-ratio는 0.1 ~ 0.5 사이여야 합니다: " + trTrendRatio);
        }
    }

    /** trTrendRatio를 기본값(0.3)으로 쓰는 생성자 */
    public AnalyticsProperties(int coldStartThreshold, int maxMatchWindow, int recentWinLossWindow) {
        this(coldStartThreshold, maxMatchWindow, recentWinLossWindow, null);
    }
}
