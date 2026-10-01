import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RankIcon } from './RankIcon'

const RANKS = ['x+', 'x', 'u', 'ss', 's+', 's', 's-', 'a+', 'a', 'a-', 'b+', 'b', 'b-', 'c+', 'c', 'c-', 'd+', 'd', 'z']
const FILES = ['x-plus', 'x', 'u', 'ss', 's-plus', 's', 's-minus', 'a-plus', 'a', 'a-minus', 'b-plus', 'b', 'b-minus', 'c-plus', 'c', 'c-minus', 'd-plus', 'd', 'z']

describe('RankIcon', () => {
  it('TETR.IO 랭크 19종마다 같은 이름의 아이콘을 쓴다', () => {
    RANKS.forEach((rank, i) => {
      const { unmount } = render(<RankIcon rank={rank} size={24} label={rank} />)
      expect(screen.getByRole('img', { name: rank }).getAttribute('src')).toMatch(new RegExp(`/${FILES[i]}\\.png$`))
      unmount()
    })
  })

  it('대문자로 와도, 모르는 값이나 빈 값이면 랭크 없음(?) 아이콘', () => {
    const src = (rank: string | undefined) => {
      const { container, unmount } = render(<RankIcon rank={rank} size={24} />)
      const v = container.querySelector('img')!.getAttribute('src')
      unmount()
      return v
    }
    expect(src('X+')).toMatch(/x-plus\.png$/)
    expect(src('?')).toMatch(/\/z\.png$/)
    expect(src(undefined)).toMatch(/\/z\.png$/)
  })

  it('label이 없으면 장식용 — 화면 낭독기에서 숨긴다', () => {
    const { container } = render(<RankIcon rank="s" size={24} />)
    expect(container.querySelector('img')).toHaveAttribute('aria-hidden', 'true')
  })
})
