import { describe, expect, it } from 'vitest'
import type { DeltaMetrics } from '../api/types'
import { evidenceOf, formSummary, mapHighlights, rankLabel, signed, trendOf } from './stats'

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

  it("랭크 'z'는 랭크 없음", () => {
    expect(rankLabel('z')).toBeNull()
    expect(rankLabel(undefined)).toBeNull()
    expect(rankLabel('x+')).toBe('X+')
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
    expect(items.map((i) => [i.ev.stat, i.sentence])).toEqual([
      ['delta_app', 'a'],
      ['comeback_rate', 'b'],
      ['tr_trend_delta', 'c'],
    ])
  })
})
