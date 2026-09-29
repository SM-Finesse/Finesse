import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App — 랜딩 → 결과 화면 전달', () => {
  it('제출한 유저명과 뷰가 다음 화면으로 넘어가고, 돌아오면 유저명이 남아 있다', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(screen.getByRole('switch'))
    await user.type(screen.getByRole('textbox', { name: '유저명' }), 'ExamplePlayer{Enter}')

    expect(screen.getByRole('heading', { name: 'ExamplePlayer 리포트를 준비합니다' })).toBeInTheDocument()
    expect(screen.getByText(/헤비 뷰로 요청했습니다/)).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.getByRole('textbox', { name: '유저명' })).toHaveValue('ExamplePlayer')
  })

  it('고른 뷰는 저장돼 다음 방문의 기본값이 된다', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<App />)
    await user.click(screen.getByRole('switch'))
    expect(localStorage.getItem('finesse.view')).toBe('heavy')
    unmount()

    render(<App />)
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true')
  })
})
