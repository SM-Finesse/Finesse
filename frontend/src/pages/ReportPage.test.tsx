import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LangProvider } from '../i18n/LangProvider'
import { COLD, COMMENT, FakeEventSource, json, pending, routeFetch, STATS } from '../test/fixtures'
import type { View } from '../types'
import { ReportPage } from './ReportPage'

afterEach(() => {
  vi.unstubAllGlobals()
})

function setup(initialView: View = 'light') {
  const onBack = vi.fn()
  function Harness() {
    const [view, setView] = useState<View>(initialView)
    return <ReportPage username="ExamplePlayer" view={view} onViewChange={setView} onBack={onBack} />
  }
  const user = userEvent.setup()
  render(
    <LangProvider initial="ko">
      <Harness />
    </LangProvider>,
  )
  return { user, onBack }
}

describe('ReportPage — 라이트 뷰', () => {
  it('stats를 먼저 그리고, 코멘트는 그 뒤에 불러와 채운다', async () => {
    const { urls } = routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    setup()

    const profile = await screen.findByRole('region', { name: 'PROFILE' })
    /* 랭크 글자는 TETR.IO 랭크 색 */
    expect(within(profile).getByText('X')).toHaveStyle({ color: '#FF45FF' })
    expect(within(profile).getByText('75승 45패')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'exampleplayer' })).toBeInTheDocument()

    /* 코멘트는 아직 오는 중 — 자리만 잡혀 있다 */
    expect(screen.getByRole('status', { name: /코멘트 생성 중/ })).toBeInTheDocument()
    expect(urls()).toEqual(['/api/v1/stats/ExamplePlayer', '/api/v1/comment/ExamplePlayer?scope=light'])
  })

  it('하이라이트마다 근거 수치를 붙이고, 근거가 없는 항목은 뺀다 (FR-05)', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [() => json(COMMENT)] })
    setup()

    expect(await screen.findByText(COMMENT.light_summary)).toBeInTheDocument()
    const cards = screen.getAllByRole('button', { pressed: false }).filter((b) => b.textContent?.includes('근거'))
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveTextContent('ΔAPP +0.092')
    expect(cards[0]).toHaveTextContent('▲')
    expect(cards[1]).toHaveTextContent('ΔVS/APM −0.055')
    expect(cards[1]).toHaveTextContent('▼')
    expect(screen.queryByText('계산되지 않은 지표를 가리키는 문장')).not.toBeInTheDocument()
  })

  it('프로필 사진 · 레벨 · 국가 · 가입일 · APM/PPS/VS를 보여주고, 사진을 못 불러오면 블록 아바타로 바꾼다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { container } = render(
      <LangProvider initial="ko">
        <ReportPage username="ExamplePlayer" view="light" onViewChange={() => {}} onBack={() => {}} />
      </LangProvider>,
    )
    const profile = await screen.findByRole('region', { name: 'PROFILE' })

    const photo = () => container.querySelector('img[src*="user-content"]')
    const img = photo()!
    expect(img).toHaveAttribute('src', STATS.profile.avatar_url)
    /* 랭크는 글자로만 — 랭크 그림은 그리지 않는다 */
    expect(container.querySelector('[data-rank]')).toBeNull()
    expect(screen.getByText(/^37\d\d$/)).toHaveAttribute('title', expect.stringMatching(/^다음 레벨까지 \d+%$/))
    await waitFor(() => expect(screen.getByRole('img', { name: '말레이시아' }).getAttribute('src')).toMatch(/svg/))
    expect(screen.getByText(/가입 \d+년 전 · 플레이 1,804시간 · 최근 120경기/)).toBeInTheDocument()
    expect(within(profile).getByText('237')).toBeInTheDocument()
    /* 이번 시즌 최고 랭크 */
    expect(within(profile).getByText('TOP RANK').parentElement).toHaveTextContent('TOP RANKX+')

    fireEvent.error(img)
    expect(photo()).toBeNull()
  })

  it('유저 정보 값이 빠지면 지어내지 않는다 — 레벨·국가 없음, APM 등은 —', async () => {
    const { best_rank: _r, avatar_url: _a, xp: _x, country: _c, joined_at: _j, play_time_seconds: _t, friend_count: _f, apm: _p, pps: _s, vs: _v, ...rest } = STATS.profile
    routeFetch({ stats: [() => json({ ...STATS, profile: rest })], comment: [pending] })
    const { container } = render(
      <LangProvider initial="ko">
        <ReportPage username="ExamplePlayer" view="light" onViewChange={() => {}} onBack={() => {}} />
      </LangProvider>,
    )
    const profile = await screen.findByRole('region', { name: 'PROFILE' })
    expect(container.querySelector('img[src*="user-content"]')).toBeNull()
    expect(screen.queryByText(/^LV/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^가입/)).not.toBeInTheDocument()
    expect(screen.queryByText(/플레이 \d/)).not.toBeInTheDocument()
    expect(screen.queryByText('TOP RANK')).not.toBeInTheDocument()
    expect(screen.queryByTitle('이 유저를 친구로 추가한 플레이어 수')).not.toBeInTheDocument()
    expect(within(profile).getAllByText('—')).toHaveLength(3)
  })

  it('친구 수를 보여주고, TETR.IO 그림(업적 메달·서포터 띠)은 백엔드가 값을 보내도 그리지 않는다', async () => {
    /* 백엔드는 아직 이 값들을 보낸다 */
    const profile = { ...STATS.profile, supporter: true, supporter_tier: 3, featured_achievements: [{ k: 8, name: '20TSD', rank: 5, pos: 3, art: 2 }] }
    routeFetch({ stats: [() => json({ ...STATS, profile })], comment: [pending] })
    setup()

    expect(await screen.findByTitle('이 유저를 친구로 추가한 플레이어 수')).toHaveTextContent('2,438')
    expect(screen.queryByRole('list', { name: '대표 업적' })).not.toBeInTheDocument()
    expect(screen.queryByRole('list', { name: '배지' })).not.toBeInTheDocument()
    expect(screen.queryByText(/SUPPORTER/)).not.toBeInTheDocument()
    expect(document.querySelector('img[src*="tetr.io/res"], [style*="tetr.io/res"]')).toBeNull()
  })

  it('랭크 기록이 없는 유저(tr·glicko·rd = -1)와 플레이 시간을 숨긴 유저(-1)도 화면이 깨지지 않고 —로 보여준다', async () => {
    const profile = { ...STATS.profile, rank: 'z', tr: -1, glicko: -1, rd: -1, play_time_seconds: -1 }
    routeFetch({ stats: [() => json({ ...STATS, profile })], comment: [pending] })
    setup()

    const panel = await screen.findByRole('region', { name: 'PROFILE' })
    expect(within(panel).getByText('랭크 없음')).toBeInTheDocument()
    expect(within(panel).getAllByText('—')).toHaveLength(2)
    expect(within(panel).queryByText(/편차/)).not.toBeInTheDocument()
    expect(screen.queryByText(/-1/)).not.toBeInTheDocument()
    expect(screen.queryByText(/플레이 -?\d/)).not.toBeInTheDocument()
  })

  it('랭크 값이 안 오면 랭크가 없다고 단정하지 않고 —로 둔다', async () => {
    const { rank: _rank, ...profile } = STATS.profile
    routeFetch({ stats: [() => json({ ...STATS, profile })], comment: [pending] })
    setup()

    const panel = await screen.findByRole('region', { name: 'PROFILE' })
    expect(within(panel).queryByText('랭크 없음')).not.toBeInTheDocument()
    expect(within(panel).getByText('RANK').nextElementSibling).toHaveTextContent('—')
  })

  it('최근 경기 승패 보드와 TR 추이를 보여준다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    setup()

    const board = await screen.findByRole('img', { name: '최근 10경기' })
    expect(board.querySelectorAll('[data-result="W"]')).toHaveLength(7)
    expect(board.querySelectorAll('[data-result="L"]')).toHaveLength(3)
    expect(screen.getByTestId('tr-chart')).toBeInTheDocument()
    expect(screen.getByText('구간 최고')).toBeInTheDocument()
  })

  it('경기는 충분한데 TR 추이 값이 안 오면, 표본 부족이 아니라 데이터 누락으로 안내한다', async () => {
    routeFetch({ stats: [() => json({ ...STATS, fixed_metrics: { ...STATS.fixed_metrics, tr_trend: [] } })], comment: [pending] })
    setup()
    expect(await screen.findByText('TR 추이 데이터를 받지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('추이를 그릴 만큼 기록이 없습니다.')).not.toBeInTheDocument()
  })

  it('콜드스타트면 LLM을 부르지 않고 데이터 부족 안내를 띄운다', async () => {
    const { urls } = routeFetch({ stats: [() => json(COLD)] })
    setup()

    expect(await screen.findByText('최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.')).toBeInTheDocument()
    /* 빈 칸뿐인 승패·TR 추이 카드는 그리지 않는다 */
    expect(screen.queryByRole('region', { name: '승패 분포' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'TR 추이' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'AI READOUT' })).not.toBeInTheDocument()
    expect(urls()).toEqual(['/api/v1/stats/ExamplePlayer'])
  })

  it('콜드스타트면 분석이 열리기까지 몇 판 남았는지 10칸 미터로 보여준다', async () => {
    routeFetch({ stats: [() => json(COLD)] })
    setup()

    const meter = await screen.findByRole('region', { name: '분석이 열리기까지' })
    expect(within(meter).getByText('3')).toBeInTheDocument()
    expect(within(meter).getByText('판 남음')).toBeInTheDocument()
    const cells = within(meter).getByRole('img', { name: '10판 중 7판 완료' }).querySelectorAll('li')
    /* 왼쪽이 가장 오래된 경기 — recent_form(최신이 먼저)을 뒤집은 순서. 다음에 채울 칸은 따로 표시 */
    expect([...cells].map((c) => c.dataset.state)).toEqual(['L', 'L', 'W', 'W', 'L', 'L', 'W', 'next', 'left', 'left'])
    expect(cells[7]).toHaveTextContent('8')
    expect(within(meter).queryByText('결과 미수신')).not.toBeInTheDocument()
    /* 지금까지 치른 7판의 승률 — 3승 4패 */
    expect(within(meter).getByLabelText('지금까지 승률')).toHaveTextContent('WIN RATE42.9%3승 4패')
  })

  it('승패 기록 없이 경기 수만 오면 치른 칸을 회색으로 채우고 범례로 알린다', async () => {
    routeFetch({ stats: [() => json({ ...COLD, match_count: 2, fixed_metrics: { win_rate: 0, tr_trend: [], recent_form: [] } })] })
    setup()

    const meter = await screen.findByRole('region', { name: '분석이 열리기까지' })
    const cells = within(meter).getByRole('img', { name: '10판 중 2판 완료' }).querySelectorAll('li')
    expect([...cells].slice(0, 3).map((c) => c.dataset.state)).toEqual(['played', 'played', 'next'])
    expect(within(meter).getByText('결과 미수신')).toBeInTheDocument()
    /* 승패 기록이 없으면 승률을 지어내지 않는다 */
    expect(within(meter).getByLabelText('지금까지 승률')).toHaveTextContent(/^WIN RATE—$/)
  })

  it('승률을 모르면(win_rate 없음) NaN 대신 —로 보여준다', async () => {
    routeFetch({ stats: [() => json({ ...COLD, match_count: 4, fixed_metrics: { tr_trend: [], recent_form: [] } })] })
    setup()

    const profile = await screen.findByRole('region', { name: 'PROFILE' })
    expect(within(profile).getByText('WIN RATE').parentElement).toHaveTextContent(/^WIN RATE—$/)
    expect(document.body).not.toHaveTextContent(/NaN/)
  })

  it('경기가 하나도 없어도 미터는 0판에서 시작한다', async () => {
    routeFetch({ stats: [() => json({ ...COLD, match_count: 0, fixed_metrics: { win_rate: 0, tr_trend: [], recent_form: [] } })] })
    setup()

    const meter = await screen.findByRole('region', { name: '분석이 열리기까지' })
    expect(within(meter).getByText('10', { selector: 'span' })).toBeInTheDocument()
    expect(within(meter).getByRole('img', { name: '10판 중 0판 완료' })).toBeInTheDocument()
  })

  it('코멘트가 실패하면 통계는 그대로 두고 다시 시도할 수 있다', async () => {
    const { urls } = routeFetch({
      stats: [() => json(STATS)],
      comment: [() => json({ error_code: 'LLM_UNAVAILABLE', message: 'x' }, 503), () => json(COMMENT)],
    })
    const { user } = setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('일시적으로 코멘트를 생성할 수 없습니다.')
    expect(screen.getByRole('region', { name: 'PROFILE' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText(COMMENT.light_summary)).toBeInTheDocument()
    expect(urls().filter((u) => u.includes('/comment/'))).toHaveLength(2)
  })
})

const busy = (retryAfter?: string) => () =>
  Promise.resolve(
    new Response(JSON.stringify({ error_code: 'SERVER_BUSY', message: '사용자가 많습니다' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json', ...(retryAfter ? { 'Retry-After': retryAfter } : {}) },
    }),
  )

describe('ReportPage — 서버 혼잡 (503 SERVER_BUSY)', () => {
  it('사용자가 많다고 안내하고, Retry-After 동안 다시 시도 버튼을 잠갔다가 풀어 준다', async () => {
    const { urls } = routeFetch({ stats: [busy('1'), () => json(STATS)], comment: [pending] })
    setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('사용자가 많습니다. 잠시 후 다시 시도해 주세요.')
    expect(screen.getByRole('button', { name: '1초 후 다시 시도' })).toBeDisabled()

    const retry = await screen.findByRole('button', { name: '다시 시도' }, { timeout: 2500 })
    expect(retry).toBeEnabled()
    /* 기다리는 동안 저절로 다시 요청하지 않는다 */
    expect(urls()).toHaveLength(1)

    fireEvent.click(retry)
    expect(await screen.findByRole('region', { name: 'PROFILE' })).toBeInTheDocument()
  })

  it('코멘트가 SERVER_BUSY면 통계는 그대로 두고 같은 안내를 띄운다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [busy('3')] })
    setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('사용자가 많습니다. 잠시 후 다시 시도해 주세요.')
    expect(screen.getByRole('button', { name: '3초 후 다시 시도' })).toBeDisabled()
    expect(screen.getByRole('region', { name: 'PROFILE' })).toBeInTheDocument()
  })
})

describe('ReportPage — 전적 갱신 · 뷰 전환', () => {
  it('R로 갱신하면 이전 결과를 보여주는 채로 refresh=true를 요청하고, 새 결과로 바꾼다', async () => {
    let resolve!: (r: Response) => void
    const { urls } = routeFetch({
      stats: [() => json(STATS), () => new Promise<Response>((r) => (resolve = r))],
      comment: [pending],
    })
    const { user } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })

    await user.keyboard('r')
    expect(urls()).toContain('/api/v1/stats/ExamplePlayer?refresh=true')
    expect(screen.getByRole('button', { name: /전적을 불러오는 중입니다/ })).toBeDisabled()
    expect(screen.getByText('75승 45패')).toBeInTheDocument()

    resolve(new Response(JSON.stringify({ ...STATS, match_count: 200 }), { status: 200 }))
    expect(await screen.findByText(/최근 200경기/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '전적 갱신' })).toBeEnabled()
  })

  it('갱신이 실패하면 이전 결과를 남기고 실패를 알린다', async () => {
    routeFetch({
      stats: [() => json(STATS), () => json({ error_code: 'TETRIO_API_UNAVAILABLE', message: 'x' }, 502)],
      comment: [pending],
    })
    const { user } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })

    await user.click(screen.getByRole('button', { name: '전적 갱신' }))
    expect(await screen.findByText('전적을 갱신하지 못했습니다. 이전 결과를 그대로 보여줍니다.')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'PROFILE' })).toBeInTheDocument()
  })

  it('H로 헤비, L로 다시 라이트 — 재조회 없이 같은 stats로 바꾼다 (FR-06)', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { urls } = routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { user } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })

    await user.keyboard('h')
    expect(screen.getByRole('heading', { name: 'TR · 능력치 추이' })).toBeInTheDocument()
    await user.keyboard('l')
    expect(screen.getByRole('region', { name: '승패 분포' })).toBeInTheDocument()
    expect(urls().filter((u) => u.includes('/stats/'))).toHaveLength(1)
  })

  it('ESC로 처음 화면으로 돌아간다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { user, onBack } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })
    await user.keyboard('{Escape}')
    expect(onBack).toHaveBeenCalledOnce()
  })
})
