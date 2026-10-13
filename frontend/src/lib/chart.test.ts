import { describe, expect, it } from 'vitest'
import { gameTicks, niceScale } from './chart'

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
  })

  it('경기가 적으면 전부', () => {
    expect(gameTicks(1)).toEqual([1])
    expect(gameTicks(2)).toEqual([1, 2])
    expect(gameTicks(5)).toEqual([1, 2, 3, 4, 5])
  })
})
