import { describe, expect, it } from 'vitest'
import { levelTagKind } from './levelTag'

describe('levelTagKind', () => {
  it('배지 색은 500레벨, 장식 모양은 100레벨, 장식 색은 10레벨마다 바뀐다 (ch.tetr.io와 같은 규칙)', () => {
    // 3582: 배지 7(파랑) · 모양 0(사선) · 장식 8(보라)
    const a = levelTagKind(3582)
    expect(a.badge).toContain('#1F6CEC')
    expect(a.itemClip).toBe(levelTagKind(0).itemClip)
    expect(a.item).toContain('#8644FF')
    // 3082: 배지 6(청록)
    expect(levelTagKind(3082).badge).toContain('#22F0DA')
    // 100레벨 단위로 장식 모양이 바뀌고 5종을 돈다
    expect(levelTagKind(100).itemClip).not.toBe(levelTagKind(0).itemClip)
    expect(levelTagKind(500).itemClip).toBe(levelTagKind(0).itemClip)
  })

  it('어두운 배지는 흰 글자, 밝은 배지는 검은 글자', () => {
    expect(levelTagKind(3582).color).toBe('#FFFD')
    expect(levelTagKind(3082).color).toBe('#000D')
  })

  it('5000레벨부터는 금색 태그', () => {
    expect(levelTagKind(5000).badge).toContain('#FFD800')
    expect(levelTagKind(7321)).toBe(levelTagKind(5000))
  })
})
