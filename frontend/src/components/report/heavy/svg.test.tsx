import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { rateColor } from '../../../lib/chart'
import { ColumnChart, CompareBars } from './svg'

const barsOf = (svg: Element, color: string) => svg.querySelectorAll(`rect[fill="${color}"]`).length

describe('ColumnChart', () => {
  it('0%면 막대를 그리지 않고 값 글자만, 0보다 크면 작아도 한 칸은 그린다', () => {
    const { container } = render(
      <ColumnChart
        aria="역전"
        max={50}
        items={[
          { k: '내 역전승률', v: 0, c: '#66C0F4' },
          { k: '역전 허용률', v: 0.5, c: '#D9524C' },
        ]}
      />,
    )
    const svg = container.querySelector('svg')!
    expect(barsOf(svg, '#66C0F4')).toBe(0)
    expect(barsOf(svg, '#D9524C')).toBe(1)
    expect(svg).toHaveTextContent('0.0%')
  })

  it('막대 꼭대기가 축 눈금과 맞는다 — 칸을 통째로 버리지 않고 맨 위 칸은 남은 높이만큼', () => {
    /* 역전 차트 천장 30% · 눈금 0·10·20·30에서 20% 막대는 20 눈금선 높이까지 (예전엔 6칸이라 17 근처에서 멈췄다) */
    const { container } = render(
      <ColumnChart aria="역전" h={250} mb={62} max={30} ticks={[0, 10, 20, 30]} items={[{ k: 'a', v: 20, c: '#D9524C' }]} />,
    )
    const svg = container.querySelector('svg')!
    const line20 = [...svg.querySelectorAll('text')].find((t) => t.textContent === '20')!.previousElementSibling!
    const top = Math.min(...[...svg.querySelectorAll('rect[fill="#D9524C"]')].map((r) => Number(r.getAttribute('y'))))
    expect(top).toBeCloseTo(Number(line20.getAttribute('y1')), 6)
  })

  it('막대가 셋 이상이면 꼭대기를 점선으로 잇고, 둘이면 잇지 않는다', () => {
    const five = render(<ColumnChart aria="5구간" items={[80, 60, 60, 40, 60].map((v, i) => ({ k: `Q${i + 1}`, v }))} />)
    expect(five.container.querySelectorAll('path[stroke-dasharray="4 4"]')).toHaveLength(1)
    const two = render(<ColumnChart aria="둘" items={[{ k: 'a', v: 30 }, { k: 'b', v: 20 }]} />)
    expect(two.container.querySelectorAll('path[stroke-dasharray="4 4"]')).toHaveLength(0)
  })

  it('색을 주지 않으면 값에 따라 5단계로 딱 끊는다 — 80·70·60·50%', () => {
    expect([82.1, 74.6, 66.2, 55.4, 41.8].map(rateColor)).toEqual(['#8FC93A', '#B4C63F', '#D3BE55', '#D28A8A', '#D9524C'])
    expect(rateColor(80)).toBe('#8FC93A')
    expect(rateColor(79.9)).toBe('#B4C63F')
    expect(rateColor(61)).toBe(rateColor(69))
  })

  it('나 vs 상대 막대 — 0선에서 시작해 음수는 왼쪽으로, 길이는 절댓값 (−20.7이 −6.5보다 길다)', () => {
    const { container } = render(
      <CompareBars me="나" opp="상대" aria="수비" rows={[{ k: 'ΔCheese Index', kr: '', mine: -20.7, opp: -6.5, decimals: 1, delta: '−14.2', trend: 'down' }]} />,
    )
    const [mine, opp] = [...container.querySelectorAll('rect[height="18"]')].map((r) => ({ x: Number(r.getAttribute('x')), w: Number(r.getAttribute('width')) }))
    expect(mine.w).toBeGreaterThan(opp.w)
    /* 두 막대 모두 0선(오른쪽 끝)에서 끝난다 */
    expect(mine.x + mine.w).toBeCloseTo(opp.x + opp.w)
    expect(container).toHaveTextContent('0')
  })

  it('양수와 음수가 섞이면 0선을 사이에 두고 반대쪽으로', () => {
    const { container } = render(<CompareBars me="나" opp="상대" aria="수비" rows={[{ k: 'Δ', kr: '', mine: 10, opp: -5, decimals: 1, delta: '+15.0', trend: 'up' }]} />)
    const [mine, opp] = [...container.querySelectorAll('rect[height="18"]')].map((r) => ({ x: Number(r.getAttribute('x')), w: Number(r.getAttribute('width')) }))
    expect(opp.x + opp.w).toBeCloseTo(mine.x)
    expect(mine.w).toBeCloseTo(opp.w * 2)
  })
})
