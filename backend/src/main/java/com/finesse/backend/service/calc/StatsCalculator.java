package com.finesse.backend.service.calc;

import com.finesse.backend.dto.StatsResponse;
import com.finesse.backend.model.NormalizedMatch;
import com.finesse.backend.model.RoundSample;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 데이터 명세서 v5 5·6절 "본 프로젝트 핵심 가공 지표" 계산.
 * matches는 TETR.IO 응답 그대로 최신순(0번이 가장 최근)으로 들어온다고 가정한다
 * (TETR.IO API 연동확인 문서에서 확인됨).
 *
 * 콜드스타트 임계값(10판)과 각 지표별 최소 표본 요건은 기능 명세서 3.4절·데이터 명세서 v5 6절을 따른다.
 */
@Component
public class StatsCalculator {

    private static final int COLD_START_THRESHOLD = 10;
    private static final int TREND_WINDOW = 10;
    private static final int QUINTILE_MIN_MATCHES = 10;
    private static final int COMEBACK_MIN_QUALIFYING = 10;
    private static final int RECENT_FORM_WINDOW = 40;

    public boolean isColdStart(int gamesPlayed) {
        return gamesPlayed < COLD_START_THRESHOLD;
    }

    public double winRate(List<NormalizedMatch> matches) {
        if (matches.isEmpty()) return 0.0;
        long wins = matches.stream().filter(m -> "victory".equals(m.result())).count();
        return (double) wins / matches.size();
    }

    /**
     * fixed_metrics.recent_form — 최근 최대 40경기 승패("W"/"L"). matches[0]이 최신이므로
     * 그대로 앞에서 40개(또는 그보다 적으면 있는 만큼) 잘라서 쓴다 — 뒤집지 않음, index 0이 가장 최근 경기.
     * 콜드스타트(매치 10판 미만)여도 있는 만큼 반환한다 — delta_metrics와 달리 표본 하한이 없는 fixed 지표.
     */
    public List<String> recentForm(List<NormalizedMatch> matches) {
        int n = Math.min(RECENT_FORM_WINDOW, matches.size());
        List<String> form = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            form.add("victory".equals(matches.get(i).result()) ? "W" : "L");
        }
        return form;
    }

    /** fixed_metrics.tr_trend — 차트용 시계열 (과거→현재 순으로 뒤집어서 반환). */
    public List<Double> trTrendSeries(List<NormalizedMatch> matches) {
        List<Double> series = new ArrayList<>();
        for (NormalizedMatch m : matches) {
            if (m.me().trAfter() != null) {
                series.add(m.me().trAfter());
            }
        }
        java.util.Collections.reverse(series); // matches는 최신순 → 차트는 과거→현재
        return series;
    }

    /** delta_metrics.tr_trend_delta — 최근 10판 평균 − 이전 10판 평균 (matches[0]이 최신). */
    public Double trTrendDelta(List<NormalizedMatch> matches) {
        List<Double> trs = matches.stream()
                .map(m -> m.me().trAfter())
                .filter(java.util.Objects::nonNull)
                .toList();
        if (trs.size() < TREND_WINDOW * 2) {
            return null;
        }
        double recent = average(trs.subList(0, TREND_WINDOW));
        double previous = average(trs.subList(TREND_WINDOW, TREND_WINDOW * 2));
        return recent - previous;
    }

    /** delta_metrics.attack / defense — 매치별 Δ 계산 후 평균 (기능 명세서 3절 "방법1" 확정). */
    public StatsResponse.Attack attackDelta(List<NormalizedMatch> matches) {
        List<Double> deltaApp = new ArrayList<>();
        List<Double> deltaWeightedApp = new ArrayList<>();
        for (NormalizedMatch m : matches) {
            Metrics me = computeFancy(m.me());
            Metrics opp = computeFancy(m.opp());
            if (me == null || opp == null) continue;
            deltaApp.add(me.app - opp.app);
            deltaWeightedApp.add(me.weightedApp - opp.weightedApp);
        }
        if (deltaApp.isEmpty()) return null;
        return new StatsResponse.Attack(average(deltaApp), average(deltaWeightedApp));
    }

    public StatsResponse.Defense defenseDelta(List<NormalizedMatch> matches) {
        List<Double> deltaVsApm = new ArrayList<>();
        List<Double> deltaCheese = new ArrayList<>();
        for (NormalizedMatch m : matches) {
            Metrics me = computeFancy(m.me());
            Metrics opp = computeFancy(m.opp());
            if (me == null || opp == null) continue;
            deltaVsApm.add(me.vsApm - opp.vsApm);
            deltaCheese.add(me.cheeseIndex - opp.cheeseIndex);
        }
        if (deltaVsApm.isEmpty()) return null;
        return new StatsResponse.Defense(average(deltaVsApm), average(deltaCheese));
    }

    /**
     * strength_split — 본인 매치 히스토리 내 (상대 TR − 본인 TR) 격차를 5등분(quintile), 최상위-최하위 승률차.
     * 구간당 10판 미만이면 후보 자체를 제외한다 (데이터 명세서 v5 6절, 2026-09-02 확정).
     */
    private record GapResult(double gap, boolean win) {
    }

    public Double strengthSplit(List<NormalizedMatch> matches) {
        List<GapResult> gaps = new ArrayList<>();
        for (NormalizedMatch m : matches) {
            Double meTr = m.me().trBefore();
            Double oppTr = m.opp().trBefore();
            if (meTr == null || oppTr == null) continue;
            gaps.add(new GapResult(oppTr - meTr, "victory".equals(m.result())));
        }
        if (gaps.size() < QUINTILE_MIN_MATCHES * 5) {
            return null;
        }
        gaps.sort(Comparator.comparingDouble(GapResult::gap));
        int n = gaps.size();
        int quintileSize = n / 5;
        if (quintileSize < QUINTILE_MIN_MATCHES) {
            return null;
        }
        double bottomWinRate = winRateOf(gaps.subList(0, quintileSize));
        double topWinRate = winRateOf(gaps.subList(n - quintileSize, n));
        return topWinRate - bottomWinRate;
    }

    private double winRateOf(List<GapResult> gapResults) {
        long wins = gapResults.stream().filter(GapResult::win).count();
        return (double) wins / gapResults.size();
    }

    /**
     * comeback_rate — 라운드별 승패 순서로 스코어 흐름을 재구성해, 2판 이상 뒤진 적이 있었던 매치 중 승리 비율.
     * 최소 10회(qualifying matches) 미만이면 후보 제외 (데이터 명세서 v5 6절, 2026-09-02 확정).
     */
    public Double comebackRate(List<NormalizedMatch> matches) {
        int qualifying = 0;
        int comebackWins = 0;
        for (NormalizedMatch m : matches) {
            int myWins = 0;
            int oppWins = 0;
            boolean wasDownByTwo = false;
            for (RoundSample r : m.rounds()) {
                if (r.meAlive() && !r.oppAlive()) {
                    myWins++;
                } else if (!r.meAlive() && r.oppAlive()) {
                    oppWins++;
                }
                if (myWins - oppWins <= -2) {
                    wasDownByTwo = true;
                }
            }
            if (wasDownByTwo) {
                qualifying++;
                if ("victory".equals(m.result())) {
                    comebackWins++;
                }
            }
        }
        if (qualifying < COMEBACK_MIN_QUALIFYING) {
            return null;
        }
        return (double) comebackWins / qualifying;
    }

    /**
     * session_vs_slope — 라운드 인덱스별 평균 VS 커브(300판 전체 집계)의 선형회귀 기울기.
     * round_curves(pps/vs)도 같은 집계에서 함께 만든다 (헤비 뷰 차트용, 하이라이트 후보 아님 — 기능 명세서 3.4절).
     */
    public StatsResponse.RoundCurves roundCurves(List<NormalizedMatch> matches) {
        java.util.Map<Integer, List<Double>> vsBuckets = new java.util.TreeMap<>();
        java.util.Map<Integer, List<Double>> ppsBuckets = new java.util.TreeMap<>();
        for (NormalizedMatch m : matches) {
            for (RoundSample r : m.rounds()) {
                if (r.me() == null) continue;
                if (r.me().vs() != null) {
                    vsBuckets.computeIfAbsent(r.index(), k -> new ArrayList<>()).add(r.me().vs());
                }
                if (r.me().pps() != null) {
                    ppsBuckets.computeIfAbsent(r.index(), k -> new ArrayList<>()).add(r.me().pps());
                }
            }
        }
        List<Double> vsCurve = vsBuckets.values().stream().map(this::average).toList();
        List<Double> ppsCurve = ppsBuckets.values().stream().map(this::average).toList();
        return new StatsResponse.RoundCurves(ppsCurve, vsCurve);
    }

    public Double sessionVsSlope(StatsResponse.RoundCurves curves) {
        List<Double> y = curves.vs();
        if (y.size() < 2) return null;
        int n = y.size();
        double meanX = (n - 1) / 2.0;
        double meanY = average(y);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - meanX) * (y.get(i) - meanY);
            den += (i - meanX) * (i - meanX);
        }
        return den == 0 ? null : num / den;
    }

    private record Metrics(double app, double vsApm, double cheeseIndex, double weightedApp) {
    }

    private Metrics computeFancy(NormalizedMatch.Side side) {
        if (side.apm() == null || side.pps() == null || side.vs() == null
                || side.apm() == 0 || side.pps() == 0) {
            return null;
        }
        double app = FancyMath.app(side.apm(), side.pps());
        double vsApm = FancyMath.vsApm(side.vs(), side.apm());
        double dsS = FancyMath.dsS(side.vs(), side.apm());
        double dsP = FancyMath.dsP(dsS, side.pps());
        double cheese = FancyMath.cheeseIndex(dsP, vsApm, app);
        double weightedApp = FancyMath.weightedApp(app, cheese);
        return new Metrics(app, vsApm, cheese, weightedApp);
    }

    private double average(List<Double> values) {
        OptionalDouble avg = values.stream().mapToDouble(Double::doubleValue).average();
        return avg.orElse(0.0);
    }
}
