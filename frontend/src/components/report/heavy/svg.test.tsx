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

  it('나 vs 상대 막대 — 음수끼리도 값이 클수록 길게, 0 눈금을 남긴다', () => {
    const { container } = render(
      <CompareBars me="나" opp="상대" aria="수비" rows={[{ k: 'ΔCheese Index', kr: '', mine: -20.7, opp: -6.5, decimals: 1, delta: '−14.2', trend: 'down' }]} />,
    )
    const [mine, opp] = [...container.querySelectorAll('rect[height="18"]')].map((r) => Number(r.getAttribute('width')))
    expect(opp).toBeGreaterThan(mine)
    expect(container).toHaveTextContent('0')
  })
})
