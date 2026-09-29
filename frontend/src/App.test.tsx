import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const STATS = {
  username: 'exampleplayer',
  cold_start: false,
  match_count: 120,
  profile: { rank: 'x', tr: 24321.6, glicko: 3000, rd: 60 },
  fixed_metrics: { win_rate: 0.625, tr_trend: [], recent_form: [] },
  round_curves: { pps: [], vs: [] },
  rivals: { items: [], page: 1, page_size: 20, total: 0 },
}

function stubFetch(...responses: (() => Promise<Response>)[]) {
  const fn = vi.fn<typeof fetch>()
  for (const r of responses) fn.mockImplementationOnce(r)
  vi.stubGlobal('fetch', fn)
  return fn
}

const json = (body: unknown, status = 200) => () =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

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
  it('제출하면 stats를 불러와 기본 지표를 보여주고, 돌아오면 유저명이 남아 있다', async () => {
    const fetchFn = stubFetch(json(STATS))
    const user = await submit('ExamplePlayer', { heavy: true })

    expect(screen.getByRole('heading', { name: 'ExamplePlayer 리포트' })).toBeInTheDocument()
    expect(await screen.findByText('62.5%')).toBeInTheDocument()
    expect(screen.getByText('X')).toBeInTheDocument()
    expect(screen.getByText(/헤비 뷰 화면은/)).toBeInTheDocument()
    expect(fetchFn.mock.calls[0][0]).toBe('/api/v1/stats/ExamplePlayer')

    await user.keyboard('{Escape}')
    expect(screen.getByRole('textbox', { name: '유저명' })).toHaveValue('ExamplePlayer')
  })

  it('응답이 오기 전에는 로딩 상태를 보여준다', async () => {
    stubFetch(() => new Promise<Response>(() => {}))
    await submit('player')
    expect(screen.getByRole('status')).toHaveTextContent('전적을 불러오는 중입니다…')
  })

  it('콜드스타트면 안내를 함께 보여준다', async () => {
    stubFetch(json({ ...STATS, cold_start: true, match_count: 7 }))
    await submit('NewPlayer')
    expect(await screen.findByText(/10판 미만/)).toBeInTheDocument()
  })

  it('없는 유저면 안내하고, 다시 시도하면 재요청한다', async () => {
    const fetchFn = stubFetch(json({ error_code: 'USER_NOT_FOUND', message: 'x' }, 404), json(STATS))
    const user = await submit('ghost_user')

    expect(await screen.findByRole('alert')).toHaveTextContent('해당 유저를 찾을 수 없습니다')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('62.5%')).toBeInTheDocument()
    expect(fetchFn).toHaveBeenCalledTimes(2)
  })

  it('서버에 닿지 못하면 연결 오류를 안내한다', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    await submit('player')
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
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
