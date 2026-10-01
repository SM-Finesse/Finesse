import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { json, pending, routeFetch, STATS } from './test/fixtures'

afterEach(() => {
  vi.unstubAllGlobals()
})

async function submit(username: string, { heavy = false } = {}) {
  const user = userEvent.setup()
  render(<App />)
  if (heavy) await user.click(screen.getByRole('switch'))
  await user.type(screen.getByRole('textbox', { name: '유저명' }), `${username}{Enter}`)
  return user
}

describe('App — 랜딩 → 결과 화면', () => {
  it('제출하면 리포트를 불러오고, 돌아오면 유저명이 남아 있다', async () => {
    const { urls } = routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const user = await submit('ExamplePlayer')

    expect(await screen.findByRole('heading', { name: 'exampleplayer' })).toBeInTheDocument()
    expect(urls()[0]).toBe('/api/v1/stats/ExamplePlayer')

    await user.keyboard('{Escape}')
    expect(screen.getByRole('textbox', { name: '유저명' })).toHaveValue('ExamplePlayer')
  })

  it('랜딩에서 고른 뷰로 결과 화면이 열린다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    await submit('ExamplePlayer', { heavy: true })
    expect(await screen.findByText('헤비 뷰는 다음 단계에서 연결됩니다.')).toBeInTheDocument()
  })

  it('응답이 오기 전에는 로딩 상태를 보여준다', async () => {
    routeFetch({ stats: [pending] })
    await submit('player')
    expect(screen.getByRole('status')).toHaveTextContent('전적을 불러오는 중입니다…')
  })

  it('없는 유저면 안내하고, 다시 시도하면 재요청한다', async () => {
    const { fn } = routeFetch({ stats: [() => json({ error_code: 'USER_NOT_FOUND', message: 'x' }, 404), () => json(STATS)], comment: [pending] })
    const user = await submit('ghost_user')

    expect(await screen.findByRole('alert')).toHaveTextContent('해당 유저를 찾을 수 없습니다')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('region', { name: 'PROFILE' })).toBeInTheDocument()
    expect(fn.mock.calls.filter(([u]) => String(u).includes('/stats/'))).toHaveLength(2)
  })

  it('서버에 닿지 못하면 연결 오류를 안내한다', async () => {
    routeFetch({ stats: [() => Promise.reject(new TypeError('Failed to fetch'))] })
    await submit('player')
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
  })

  it('조회에 성공한 유저만 최근 검색에 남고, 다음 방문에도 유지된다', async () => {
    routeFetch({
      stats: [() => json({ error_code: 'USER_NOT_FOUND', message: 'x' }, 404), () => json(STATS)],
      comment: [pending],
    })
    const user = await submit('ghost_user')
    await screen.findByRole('alert')
    await user.keyboard('{Escape}')
    expect(screen.getByText('검색한 유저가 여기에 쌓입니다')).toBeInTheDocument()

    const input = screen.getByRole('textbox', { name: '유저명' })
    await user.clear(input)
    await user.type(input, 'ExamplePlayer{Enter}')
    await screen.findByRole('region', { name: 'PROFILE' })
    await user.keyboard('{Escape}')
    expect(screen.getByRole('list', { name: '최근 검색' })).toHaveTextContent('exampleplayer')
    expect(JSON.parse(localStorage.getItem('finesse.recent') ?? '[]')).toEqual(['exampleplayer'])
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
