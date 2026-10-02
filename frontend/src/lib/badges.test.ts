import { describe, expect, it } from 'vitest'
import { badgeUrl, groupBadges } from './badges'

describe('groupBadges', () => {
  it('같은 group끼리 묶고, 묶음은 마지막으로 나온 자리 순 (ch.tetr.io와 같은 순서)', () => {
    const groups = groupBadges([
      { id: 'a', group: 'g' },
      { id: 'b' },
      { id: 'c', group: 'g' },
      { id: 'd' },
    ])
    expect(groups.map((g) => g.map((b) => b.id))).toEqual([['b'], ['a', 'c'], ['d']])
  })

  it('group이 없으면 id로 묶는다 — 같은 배지를 두 번 받으면 겹친다', () => {
    expect(groupBadges([{ id: 'x' }, { id: 'x' }]).map((g) => g.length)).toEqual([2])
  })

  it('배지 id는 주소에 안전하게 넣는다', () => {
    expect(badgeUrl('wpl_1')).toBe('https://tetr.io/res/badges/wpl_1.png')
    expect(badgeUrl('a/../b')).toBe('https://tetr.io/res/badges/a%2F..%2Fb.png')
  })
})
