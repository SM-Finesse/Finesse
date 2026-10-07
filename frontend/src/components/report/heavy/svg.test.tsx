import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ColumnChart } from './svg'

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
})
