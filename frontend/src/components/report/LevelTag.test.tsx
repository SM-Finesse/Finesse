import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LevelTag } from './LevelTag'

describe('LevelTag', () => {
  it('레벨 숫자를 쉼표 없이 보여주고 장식은 화면 낭독기에서 숨긴다', () => {
    const { container } = render(<LevelTag level={3582} title="다음 레벨까지 40%" />)
    expect(screen.getByText('3582')).toHaveAttribute('title', '다음 레벨까지 40%')
    container.querySelectorAll('i').forEach((i) => expect(i).toHaveAttribute('aria-hidden', 'true'))
  })
})
