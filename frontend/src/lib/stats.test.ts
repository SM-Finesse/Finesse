import { describe, expect, it } from 'vitest'
import type { DeltaMetrics } from '../api/types'
import { countryName, evidenceOf, formSummary, levelFromXp, mapHighlights, numOrDash, rankLabel, signed, timeAgo, trendOf } from './stats'

const DELTA: DeltaMetrics = {
  tr_trend_delta: 12.34,
  playstyle_relative: {},
  attack: { delta_app: 0.0919, delta_weighted_app: -0.0004 },
  defense: { delta_vs_apm: -0.0553, delta_cheese_index: -16.17 },
  strength_split: -0.2778,
  comeback_rate: 0.5556,
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
        { stat: 'comeback_rate', sentence: 'b' },
        { stat: 'tr_trend_delta', sentence: 'c' },
        { stat: 'strength_split', sentence: 'd' },
      ],
      DELTA,
    )
    /* tr_trend_delta는 후보가 아니라 매핑 실패로 빠지고, 그 자리를 strength_split이 채운다 */
    expect(items.map((i) => [i.ev.stat, i.sentence])).toEqual([
      ['delta_app', 'a'],
      ['comeback_rate', 'b'],
      ['strength_split', 'd'],
    ])
  })

  it('하이라이트 후보 11개가 아닌 지표는 값이 있어도 매핑 실패로 뺀다 (기능 명세 3.4·3.5절)', () => {
    const items = mapHighlights(
      [
        { stat: 'tr_trend_delta', sentence: 'TR 추이와 겹친다' },
        { stat: 'comeback_rate_against', sentence: '헤비 뷰 전용' },
        { stat: 'delta_vs_apm', sentence: 'ok' },
      ],
      { ...DELTA, comeback_rate_against: 0.25 },
    )
    expect(items.map((i) => i.ev.stat)).toEqual(['delta_vs_apm'])
  })
})
