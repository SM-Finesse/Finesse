import { describe, expect, it } from 'vitest'
import { achievementRank, frameUrl, iconStyle, wreathUrl } from './achievements'

describe('업적 메달 (ch.tetr.io CreateAchievement와 같은 규칙)', () => {
  it('등급 번호 → 테두리 그림, 모르는 값은 none', () => {
    expect(frameUrl(5)).toBe('https://tetr.io/res/achievements/frames/diamond.png')
    expect(frameUrl(100)).toBe('https://tetr.io/res/achievements/frames/issued.png')
    expect(achievementRank(42)).toBe('none')
  })

  it('화환은 경쟁 업적(art 2)이 Top 100 안일 때만 — 순위 구간마다 다르다', () => {
    const w = (pos: number, art = 2) => wreathUrl({ art, pos })?.replace('https://tetr.io/res/achievements/wreaths/', '') ?? null
    expect(w(0)).toBe('t3.png')
    expect(w(2)).toBe('t3.png')
    expect(w(3)).toBe('t5.png')
    expect(w(12)).toBe('t25.png')
    expect(w(99)).toBe('t100.png')
    expect(w(100)).toBeNull()
    expect(w(-1)).toBeNull()
    expect(w(0, 1)).toBeNull()
  })

  it('아이콘은 시트 한 장에 64개(8×8) — k번째 칸을 가리킨다', () => {
    expect(iconStyle(1)).toMatchObject({ backgroundImage: "url('https://tetr.io/res/achievements/icons/0.png')", backgroundPosition: '0% 0%' })
    expect(iconStyle(19).backgroundPosition).toBe('-200% -200%')
    expect(iconStyle(65)).toMatchObject({ backgroundImage: "url('https://tetr.io/res/achievements/icons/1.png')", backgroundPosition: '0% -800%' })
  })
})
