import { describe, expect, it } from 'vitest'
import type { DeltaMetrics, StatsResponse } from '../api/types'
import { countryName, evidenceOf, formSummary, levelFromXp, mapHighlights, linkSummary, numOrDash, rankLabel, readable, signed, timeAgo, trAfterSeries, trendOf, winRateDeltaPp, withAfterTr } from './stats'

const DELTA: DeltaMetrics = {
  tr_trend_delta: 12.34,
  playstyle_relative: {},
  attack: { delta_app: 0.0919, delta_weighted_app: -0.0004 },
  defense: { delta_vs_apm: -0.0553, delta_cheese_index: -16.17 },
  strength_split: -0.2778,
  comeback_rate: 0.5556,
  delta_comeback: 0.3056,
}

describe('표시 규칙', () => {
  it('표시 자릿수에서 0이 되는 값은 동등(≈)으로 본다', () => {
    expect(trendOf(0.04, 1)).toBe('even')
    expect(trendOf(0.06, 1)).toBe('up')
    expect(trendOf(-0.06, 1)).toBe('down')
    expect(signed(-1.25, 2)).toBe('−1.25')
    expect(signed(0.0001, 2)).toBe('±0.00')
  })

  it('빠졌거나 음수(TETR.IO의 -1)인 값은 —', () => {
    expect(numOrDash(24321.6, 2)).toBe('24,321.60')
    expect(numOrDash(0)).toBe('0')
    expect(numOrDash(-1, 2)).toBe('—')
    expect(numOrDash(undefined)).toBe('—')
  })

  it("랭크 'z'(언랭크)는 '?', 값이 없으면 null", () => {
    expect(rankLabel('z')).toBe('?')
    expect(rankLabel(undefined)).toBeNull()
    expect(rankLabel('x+')).toBe('X+')
  })

  it('XP로 레벨과 다음 레벨까지 진행률을 계산한다', () => {
    expect(levelFromXp(0)).toEqual({ level: 1, progress: 0 })
    /* 500 XP → (1)^0.6 + 500/5000 + 1 = 2.1 */
    const lv2 = levelFromXp(500)
    expect(lv2.level).toBe(2)
    expect(lv2.progress).toBeCloseTo(0.1, 10)
    /* 400만 XP를 넘으면 뒤쪽 항의 분모가 커져 레벨이 느리게 오른다 */
    expect(levelFromXp(30503753.95).level).toBe(3705)
  })

  it('지난 시각을 상대 시간으로 쓴다', () => {
    const now = Date.parse('2026-10-01T12:00:00Z')
    expect(timeAgo('2020-09-01T00:00:00Z', 'ko', now)).toBe('6년 전')
    expect(timeAgo('2026-10-01T11:48:00Z', 'ko', now)).toBe('12분 전')
    expect(timeAgo('2026-10-01T11:48:00Z', 'en', now)).toBe('12 minutes ago')
    expect(timeAgo('not a date', 'ko', now)).toBeNull()
  })

  it('국가 코드를 이름으로, 표준에 없는 코드는 그대로', () => {
    expect(countryName('KR', 'ko')).toBe('대한민국')
    expect(countryName('my', 'en')).toBe('Malaysia')
    expect(countryName('XM', 'ko')).toBe('XM')
  })

  it('최근 승패를 센다', () => {
    expect(formSummary(['W', 'L', 'W', 'W'])).toEqual({ games: 4, wins: 3, losses: 1, rate: 0.75 })
    expect(formSummary([])).toEqual({ games: 0, wins: 0, losses: 0, rate: 0 })
  })
})

describe('하이라이트 근거 (FR-05)', () => {
  it('Δ 지표는 부호와 방향, 비율 지표는 %로 방향 없이', () => {
    expect(evidenceOf('delta_app', DELTA)).toMatchObject({ text: '+0.092', trend: 'up' })
    expect(evidenceOf('delta_vs_apm', DELTA)).toMatchObject({ text: '−0.055', trend: 'down' })
    expect(evidenceOf('delta_weighted_app', DELTA)).toMatchObject({ trend: 'even' })
    expect(evidenceOf('tr_trend_delta', DELTA)).toMatchObject({ text: '+12.3 TR', trend: 'up' })
    expect(evidenceOf('comeback_rate', DELTA)).toMatchObject({ text: '55.6%', trend: null })
    expect(evidenceOf('strength_split', DELTA)).toMatchObject({ text: '−27.8%p', trend: 'down' })
    expect(evidenceOf('delta_comeback', DELTA)).toMatchObject({ text: '+30.6%p', trend: 'up' })
  })

  it('없는 키 · null 값 · delta_metrics 없음은 매핑 실패', () => {
    expect(evidenceOf('no_such_stat', DELTA)).toBeNull()
    expect(evidenceOf('delta_plonk', DELTA)).toBeNull()
    expect(evidenceOf('session_vs_slope', DELTA)).toBeNull()
    expect(evidenceOf('delta_app', undefined)).toBeNull()
  })

  it('매핑에 실패한 항목만 빼고, 같은 지표는 한 번만, 최대 3개', () => {
    const items = mapHighlights(
      [
        { stat: 'delta_plonk', sentence: 'x' },
        { stat: 'delta_app', sentence: 'a' },
        { stat: 'delta_app', sentence: 'dup' },
        { stat: 'delta_comeback', sentence: 'b' },
        { stat: 'tr_trend_delta', sentence: 'c' },
        { stat: 'strength_split', sentence: 'd' },
      ],
      DELTA,
    )
    /* tr_trend_delta는 후보가 아니라 매핑 실패로 빠지고, 그 자리를 strength_split이 채운다 */
    expect(items.map((i) => [i.ev.stat, i.sentence])).toEqual([
      ['delta_app', 'a'],
      ['delta_comeback', 'b'],
      ['strength_split', 'd'],
    ])
  })

  it('하이라이트 후보 11개가 아닌 지표는 값이 있어도 매핑 실패로 뺀다 (기능 명세 3.4·3.5절)', () => {
    const items = mapHighlights(
      [
        { stat: 'tr_trend_delta', sentence: 'TR 추이와 겹친다' },
        { stat: 'comeback_rate', sentence: '헤비 뷰 전용 — 역전은 delta_comeback만 후보' },
        { stat: 'comeback_rate_against', sentence: '헤비 뷰 전용' },
        { stat: 'delta_vs_apm', sentence: 'ok' },
      ],
      { ...DELTA, comeback_rate_against: 0.25 },
    )
    expect(items.map((i) => i.ev.stat)).toEqual(['delta_vs_apm'])
  })
})

describe('readable — AI 문장 속 내부 키 이름 (QA BUG-04)', () => {
  it('밑줄 있는 키는 화면 이름으로 바꾼다', () => {
    expect(readable('delta_opener는 opener의 성향, delta_inf_ds는 inf_ds의 성향입니다.')).toBe('ΔOpener는 Opener의 성향, ΔInf DS는 Inf DS의 성향입니다.')
    expect(readable('Your delta_comeback and session_vs_slope are high.')).toBe('Your ΔComeback and VS Slope are high.')
  })

  it('밑줄 없는 낱말은 한국어 문장에서만 바꾼다 — 영어에서는 평범한 단어일 수 있다', () => {
    expect(readable('plonk와 comeback이 강합니다.')).toBe('Plonk와 Comeback이 강합니다.')
    expect(readable('A strong comeback in the opener.')).toBe('A strong comeback in the opener.')
  })

  it('LLM이 옮긴 긴 소수는 화면 자릿수로 줄이고, 짧은 소수·정수는 그대로 둔다 (실제 화면 사례)', () => {
    expect(readable('VS/APM 차이(-0.0106634)는 낮고, 기울기가 +0.541456, Cheese Index 차이(-4.48321)')).toBe('VS/APM 차이(-0.011)는 낮고, 기울기가 +0.541, Cheese Index 차이(-4.48)')
    expect(readable('29%p 낮고 +1221.3 TR, 0.05 차이')).toBe('29%p 낮고 +1221.3 TR, 0.05 차이')
  })

  it('모르는 단어나 대문자로 쓴 이름은 그대로 둔다', () => {
    expect(readable('APM과 PPS가 높고 hello_world는 그대로.')).toBe('APM과 PPS가 높고 hello_world는 그대로.')
  })

  it('언어는 화면이 아니라 낱말 앞뒤로 — 한국어 화면에 온 영어 문장의 attack · defense는 그대로 (실제 화면 사례)', () => {
    expect(readable("The player's attack and defense metrics show delta_opener gains.")).toBe("The player's attack and defense metrics show ΔOpener gains.")
    expect(readable('attack이 강하고 defense는 약합니다.')).toBe('공격이 강하고 수비는 약합니다.')
  })

  it('헤비 챕터 id · 응답 필드 이름도 화면 이름으로 (QA 2차)', () => {
    expect(readable('tr_trend 챕터에서 playstyle과 rivals를 보면 round_curves가 오른다.')).toBe('TR 추이 챕터에서 플레이스타일과 자주 만난 상대를 보면 라운드별 곡선가 오른다.')
    expect(readable('strength_split 챕터와 session_vs_slope')).toBe('Strength Split 챕터와 VS Slope')
    expect(readable('Your tr_trend and win_rate are up; your attack is fine.')).toBe('Your TR trend and win rate are up; your attack is fine.')
  })
})
describe('linkSummary — 총평 구절 ↔ 하이라이트 카드', () => {
  const linked = (text: string, stats: Parameters<typeof linkSummary>[1], lang: 'ko' | 'en' = 'ko') =>
    linkSummary(text, stats, lang).flatMap((p) => (p.stat ? [[p.stat, p.text]] : []))
  const linked2 = (text: string, stats: Parameters<typeof linkSummary>[1], sentences: Parameters<typeof linkSummary>[3]) =>
    linkSummary(text, stats, 'ko', sentences).flatMap((p) => (p.stat ? [[p.stat, p.text]] : []))

  it('지표를 가리키는 말부터 구절 끝(…고 / …다)까지 묶고, 문장부호는 뺀다', () => {
    const text = '공격은 앞서고 수비는 밀립니다. 그런데도 승률이 68%인 건 뒤진 판을 자주 뒤집기 때문입니다.'
    expect(linked(text, ['delta_app', 'delta_vs_apm', 'delta_comeback'])).toEqual([
      ['delta_app', '공격은 앞서고'],
      ['delta_vs_apm', '수비는 밀립니다'],
      ['delta_comeback', '뒤집기 때문입니다'],
    ])
  })

  it('나눈 조각을 이으면 원문 그대로', () => {
    const text = '초반 전개가 강하고, 라운드가 갈수록 처지는 경향이 있다.'
    const parts = linkSummary(text, ['delta_opener', 'session_vs_slope', 'delta_cheese_index'], 'ko')
    expect(parts.map((p) => p.text).join('')).toBe(text)
    expect(parts.filter((p) => p.stat).map((p) => p.stat)).toEqual(['delta_opener', 'session_vs_slope'])
  })

  it('긴 표현이 먼저 — 공격 대비 방어는 ΔAPP가 아니라 ΔVS/APM', () => {
    expect(linked('공격 대비 방어 비율이 높습니다.', ['delta_app', 'delta_vs_apm'])).toEqual([['delta_vs_apm', '공격 대비 방어 비율이 높습니다']])
  })

  it('가리키는 말이 없으면 구절이 없다 — 화면은 카드 이름 칩으로 대신한다', () => {
    expect(linked('TETR.IO 게임에서 한국어로 코멘트를 작성합니다.', ['delta_opener', 'delta_cheese_index', 'session_vs_slope'])).toEqual([])
  })

  it('지표 이름이 안 나와도 카드 문장과 낱말이 겹치는 구절로 잇는다 (실제 화면 사례)', () => {
    const text = '전적 데이터에 따르면, 테트리스 게임 TETR.IO의 플레이스타일은 상대보다 우위를 보이고 있으며, 라운드 진행될수록 땡기며, 강한 상대에게 무너진 승률이 크다.'
    const sentences = {
      delta_opener: '상대보다 우위를 보는 플레이스타일',
      session_vs_slope: '강해지는 추세',
      strength_split: '강한 상대에게 무너진 승률이 크다',
    }
    expect(linked2(text, ['delta_opener', 'session_vs_slope', 'strength_split'], sentences)).toEqual([
      ['delta_opener', '플레이스타일은 상대보다 우위를 보이고 있으며'],
      ['session_vs_slope', '라운드 진행될수록 땡기며'],
      ['strength_split', '강한 상대에게 무너진 승률이 크다'],
    ])
  })

  it('카드 문장과 겹치는 말이 한 개뿐이거나 흔한 말(상대 · 경기)뿐이면 잇지 않는다', () => {
    expect(linked2('상대보다 경기를 잘 풀어 갑니다.', ['delta_plonk'], { delta_plonk: '상대보다 경기 운영이 느립니다' })).toEqual([])
  })

  it('영어 — 대소문자 무시', () => {
    expect(linked('Your Opener is strong, but defense lags.', ['delta_opener', 'delta_vs_apm'], 'en')).toEqual([
      ['delta_opener', 'Opener is strong'],
      ['delta_vs_apm', 'defense lags'],
    ])
  })
})

describe('winRateDeltaPp — 승률 변화율(%)을 승률 차(%p)로', () => {
  it('최근 30판 21승(70%) · 나머지 70판 35승(50%) → 변화율 +40% = +20%p', () => {
    expect(winRateDeltaPp(56 / 100, 100, 30, 40)).toBeCloseTo(20, 9)
  })

  it('최근 30판 12승(40%) · 나머지 70판 42승(60%) → 변화율 −33.3% = −20%p', () => {
    expect(winRateDeltaPp(54 / 100, 100, 30, (-1 / 3) * 100)).toBeCloseTo(-20, 9)
  })

  it('변화가 없으면 0%p', () => {
    expect(winRateDeltaPp(0.6, 100, 30, 0)).toBe(0)
  })

  it('나눌 구간이 없으면 null', () => {
    expect(winRateDeltaPp(0.5, 30, 30, 10)).toBeNull()
    expect(winRateDeltaPp(0.5, 100, 0, 10)).toBeNull()
  })
})

describe('trAfterSeries — TR 추이를 경기 후 TR로', () => {
  it('한 칸씩 당기고 마지막은 지금 TR', () => {
    expect(trAfterSeries([24000, 24100, 24050], 24400)).toEqual([24100, 24050, 24400])
  })

  it('지금 TR을 모르거나(언랭크 -1) 시계열이 비면 받은 그대로', () => {
    expect(trAfterSeries([24000, 24100], -1)).toEqual([24000, 24100])
    expect(trAfterSeries([], 24400)).toEqual([])
  })
})

describe('withAfterTr — 응답의 TR 값을 모두 경기 후 기준으로', () => {
  /* 매치 전 TR [100, 110, 120, 130, 140] + 지금 TR 150 → 경기 후 TR [110, 120, 130, 140, 150] */
  const base = {
    profile: { tr: 150, glicko: 0, rd: 0, window_delta: { recent_matches: 2, tr_delta_pct: 99 } },
    fixed_metrics: { tr_trend: [100, 110, 120, 130, 140], recent_form: [] },
    delta_metrics: { tr_trend_delta: 99, tr_trend_basis: { recent_matches: 2, total_matches: 5, recent_avg_tr: 0, overall_avg_tr: 0 } },
  } as unknown as StatsResponse

  it('그래프 · 프로필 TR 배지는 경기 후 시계열로, ΔTR · 근거 평균은 AI 코멘트와 맞게 백엔드 값 그대로', () => {
    const out = withAfterTr(base)
    expect(out.fixed_metrics.tr_trend).toEqual([110, 120, 130, 140, 150])
    expect(out.delta_metrics).toBe(base.delta_metrics)
    /* 최근 2판 평균 145 vs 나머지 평균 120 → +20.83% */
    expect(out.profile.window_delta?.tr_delta_pct).toBeCloseTo(20.833, 3)
  })

  it('지금 TR을 모르면(언랭크 -1) 응답을 그대로 둔다', () => {
    const unranked = { ...base, profile: { ...base.profile, tr: -1 } }
    expect(withAfterTr(unranked)).toBe(unranked)
  })
})
