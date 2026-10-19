import { describe, expect, it } from 'vitest'
import { RANK_COLORS, rankColor } from './rankColors'

describe('rankColor', () => {
  it('랭크 19종 모두 색이 있고, 대문자로 와도 같은 색', () => {
    expect(Object.keys(RANK_COLORS)).toHaveLength(19)
    expect(rankColor('X+')).toBe(RANK_COLORS['x+'])
    expect(rankColor('u')).toBe('#FF3813')
  })

  it('모르는 값이나 빈 값이면 색을 정하지 않는다', () => {
    expect(rankColor('?')).toBeUndefined()
    expect(rankColor(undefined)).toBeUndefined()
  })
})
