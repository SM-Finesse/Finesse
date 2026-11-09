import { describe, expect, it } from 'vitest'
import { ceilingTicks, comebackCeiling, gameTicks, niceScale } from './chart'

/* QA BUG-05 — TR 차트 눈금이 빠지거나 그릴 때마다 바뀌던 문제. 눈금을 값만으로 정한다 */
describe('niceScale — Y축', () => {
  it('값 범위를 1·2·5 단위 눈금으로 감싼다', () => {
    expect(niceScale(24512, 24846)).toEqual({ domain: [24500, 24900], ticks: [24500, 24600, 24700, 24800, 24900] })
    expect(niceScale(10120, 12980)).toEqual({ domain: [10000, 13000], ticks: [10000, 11000, 12000, 13000] })
  })

  it('최고값이 항상 범위 안 — 맨 위 눈금이 값보다 낮게 잘리지 않는다', () => {
    const { domain, ticks } = niceScale(24600.4, 24846.9)
    expect(domain[1]).toBeGreaterThanOrEqual(24846.9)
    expect(ticks.at(-1)).toBe(domain[1])
  })

  it('값이 하나뿐이거나 범위가 작아도 눈금 간격은 1 이상, 위아래로 한 칸씩', () => {
    expect(niceScale(500, 500)).toEqual({ domain: [499, 501], ticks: [499, 500, 501] })
    expect(niceScale(100.2, 100.6).ticks.every(Number.isInteger)).toBe(true)
  })

  it('minStep을 낮추면 소수 눈금 — 떠도는 소수 오차 없이 (0.30000000000000004가 아니라 0.3)', () => {
    expect(niceScale(0.12, 0.38, 4, 0.01)).toEqual({ domain: [0.1, 0.4], ticks: [0.1, 0.2, 0.3, 0.4] })
  })
})

describe('gameTicks — X축', () => {
  it('1과 마지막 경기, 그 사이 1·2·5 단위 배수', () => {
    expect(gameTicks(40)).toEqual([1, 10, 20, 30, 40])
    expect(gameTicks(86)).toEqual([1, 20, 40, 60, 86])
    expect(gameTicks(300)).toEqual([1, 100, 200, 300])
  })

  it('양 끝에 바짝 붙는 배수는 뺀다', () => {
    expect(gameTicks(41)).toEqual([1, 10, 20, 30, 41])
    expect(gameTicks(11)).toEqual([1, 4, 6, 8, 11])
    /* 헤비 자세히 보기에서 끝 숫자가 겹치던 판수 (151·153, 99·100, 196·200) */
    expect(gameTicks(153)).toEqual([1, 50, 100, 153])
    expect(gameTicks(100)).toEqual([1, 20, 40, 60, 80, 100])
    expect(gameTicks(200)).toEqual([1, 50, 100, 150, 200])
    expect(gameTicks(253)).toEqual([1, 100, 200, 253])
  })

  it('경기가 적으면 전부', () => {
    expect(gameTicks(1)).toEqual([1])
    expect(gameTicks(2)).toEqual([1, 2])
    expect(gameTicks(5)).toEqual([1, 2, 3, 4, 5])
  })
})

describe('comebackCeiling — 역전 차트 천장', () => {
  it('구간별: 5% 아래 20 · 5~35% (2m+50)/3 · 35~40% 40 · 40% 이상 m', () => {
    expect(comebackCeiling(0)).toBe(20)
    expect(comebackCeiling(4)).toBe(20)
    expect(comebackCeiling(20)).toBe(30)
    expect(comebackCeiling(25)).toBeCloseTo(33.333, 3)
    expect(comebackCeiling(37)).toBe(40)
    expect(comebackCeiling(55.6)).toBe(55.6)
    expect(comebackCeiling(100)).toBe(100)
  })

  it('경계에서 끊기지 않는다 (5 → 20 · 35 → 40 · 40 → 40)', () => {
    for (const b of [5, 35, 40]) expect(comebackCeiling(b - 1e-9)).toBeCloseTo(comebackCeiling(b), 6)
    expect(comebackCeiling(5)).toBe(20)
    expect(comebackCeiling(35)).toBe(40)
    expect(comebackCeiling(40)).toBe(40)
  })

  it('천장은 언제나 큰 값 이상 — 막대가 천장을 넘지 않는다', () => {
    for (let m = 0; m <= 100; m += 0.5) expect(comebackCeiling(m)).toBeGreaterThanOrEqual(m)
  })
})

describe('ceilingTicks — 천장 아래 눈금', () => {
  it('사이는 1·2·5 단위, 맨 위는 천장 값', () => {
    expect(ceilingTicks(20)).toEqual([0, 5, 10, 15, 20])
    expect(ceilingTicks(26.7)).toEqual([0, 10, 20, 26.7])
    expect(ceilingTicks(36.7)).toEqual([0, 10, 20, 30, 36.7])
    expect(ceilingTicks(40)).toEqual([0, 10, 20, 30, 40])
    expect(ceilingTicks(55.6)).toEqual([0, 20, 40, 55.6])
    expect(ceilingTicks(100)).toEqual([0, 20, 40, 60, 80, 100])
  })

  it('천장에 바짝 붙는 눈금은 뺀다 — 글자가 겹치지 않게', () => {
    expect(ceilingTicks(41)).toEqual([0, 10, 20, 30, 41])
    expect(ceilingTicks(21)).toEqual([0, 5, 10, 15, 21])
  })
})
