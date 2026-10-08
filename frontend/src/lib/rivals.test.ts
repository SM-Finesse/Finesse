import { describe, expect, it } from 'vitest'
import type { RivalItem } from '../api/types'
import { rivalTag, sortRivals } from './rivals'

const r = (nickname_masked: string, matches: number, last_match_at?: string): RivalItem => ({ nickname_masked, matches, wins: 0, losses: matches, last_match_at })

describe('자주 만난 상대 (FR-08)', () => {
  it('조우 횟수 내림차순, 같으면 최근에 대전한 상대가 위, 시각이 없으면 아래', () => {
    const sorted = sortRivals([
      r('old', 5, '2026-09-01T00:00:00Z'),
      r('none', 5),
      r('many', 9, '2026-01-01T00:00:00Z'),
      r('new', 5, '2026-10-01T00:00:00Z'),
    ])
    expect(sorted.map((x) => x.nickname_masked)).toEqual(['many', 'new', 'old', 'none'])
  })

  it('5경기 미만은 태그를 달지 않는다', () => {
    expect(rivalTag({ nickname_masked: 'a', matches: 4, wins: 0, losses: 4 })).toBeNull()
    expect(rivalTag({ nickname_masked: 'a', matches: 5, wins: 1, losses: 4 })).toBe('bad')
    expect(rivalTag({ nickname_masked: 'a', matches: 6, wins: 4, losses: 2 })).toBe('good')
  })
})
