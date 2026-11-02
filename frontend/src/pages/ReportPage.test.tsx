import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

/* 총평은 구절 버튼으로 쪼개져 그려진다 — 문단 전체 글자로 찾는다 */
const findSummary = () => screen.findByText((_, el) => el?.tagName === 'P' && el.textContent === COMMENT.light_summary)

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
    expect(within(profile).getByText('300승 100패')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'exampleplayer' })).toBeInTheDocument()

    /* 코멘트는 아직 오는 중 — 자리만 잡혀 있다 */
    expect(screen.getByRole('status', { name: /코멘트 생성 중/ })).toBeInTheDocument()
    expect(urls()).toEqual(['/api/v1/stats/ExamplePlayer', '/api/v1/comment/ExamplePlayer?scope=light'])
  })

  it('하이라이트마다 근거 수치를 붙이고, 근거가 없는 항목은 뺀다 (FR-05)', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [() => json(COMMENT)] })
    setup()

    expect(await findSummary()).toBeInTheDocument()
    const cards = screen.getAllByRole('button', { pressed: false }).filter((b) => b.textContent?.includes('근거'))
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveTextContent('ΔAPP +0.092')
    expect(cards[0]).toHaveTextContent('▲')
    expect(cards[1]).toHaveTextContent('ΔVS/APM −0.055')
    expect(cards[1]).toHaveTextContent('▼')
    expect(screen.queryByText('계산되지 않은 지표를 가리키는 문장')).not.toBeInTheDocument()
  })

  it('총평 속 지표 구절을 누르면 그 하이라이트 지표 창이 뜨고, 아래 카드도 켜진다 — 다시 누르거나 Esc면 닫힌다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [() => json(COMMENT)] })
    const { user, onBack } = setup()
    await findSummary()
    const readout = screen.getByRole('region', { name: 'AI READOUT' })
    /* "공격은 앞서고 수비는 밀립니다." — 공격 → ΔAPP, 수비 → ΔVS/APM. 나머지 말은 클릭할 수 없다 */
    const atk = within(readout).getByRole('button', { name: '공격은 앞서고' })
    const def = within(readout).getByRole('button', { name: '수비는 밀립니다' })
    expect(within(readout).getAllByRole('button')).toHaveLength(2)
    const card = (code: string) => screen.getAllByRole('button').find((b) => b.id.startsWith('hl-') && b.textContent?.includes(code))!

    /* 마우스만 올려서는 아무것도 뜨지 않는다 */
    await user.hover(atk)
    expect(within(readout).queryByRole('region')).not.toBeInTheDocument()

    await user.click(atk)
    const panel = within(readout).getByRole('region', { name: '공격 효율 근거 지표' })
    expect(panel).toHaveTextContent('ΔAPP +0.092')
    expect(panel).toHaveTextContent('같은 블록 수로 상대보다 공격을 더 만듭니다.')
    expect(atk).toHaveAttribute('aria-expanded', 'true')
    expect(card('ΔAPP')).toHaveAttribute('aria-pressed', 'true')
    expect(card('ΔVS/APM')).toHaveClass('opacity-50')

    /* 다른 구절을 누르면 그 지표로 바뀐다 */
    await user.click(def)
    expect(within(readout).getByRole('region', { name: '공격 대비 방어 비율 근거 지표' })).toHaveTextContent('ΔVS/APM −0.055')
    await user.click(def)
    expect(within(readout).queryByRole('region')).not.toBeInTheDocument()

    await user.click(atk)
    await user.keyboard('{Escape}')
    expect(within(readout).queryByRole('region')).not.toBeInTheDocument()
    expect(card('ΔAPP')).toHaveAttribute('aria-pressed', 'false')
    expect(onBack).not.toHaveBeenCalled()
  })

  it('총평에 지표를 가리키는 말이 없으면 클릭할 곳 없이 평문으로 둔다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [() => json({ ...COMMENT, light_summary: 'TETR.IO 게임에서 한국어로 코멘트를 작성합니다.' })] })
    setup()
    expect(await screen.findByText('TETR.IO 게임에서 한국어로 코멘트를 작성합니다.')).toBeInTheDocument()
    const readout = screen.getByRole('region', { name: 'AI READOUT' })
    expect(within(readout).queryByRole('button')).not.toBeInTheDocument()
    expect(within(readout).queryByText('밑줄 친 말을 누르면 근거 지표가 뜹니다')).not.toBeInTheDocument()
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
    /* 분석한 경기 수는 헤더에서 빠지고 AI READOUT 아래 GAMES 줄로 */
    /* 플레이 시간만 진하게 따로 감싼다 — 줄 전체는 한 문장으로 읽혀야 한다 */
    const playTime = screen.getByText('플레이 1,804시간')
    expect(playTime).toHaveClass('font-semibold')
    expect(playTime.parentElement).toHaveTextContent(/^가입 \d+년 전 · 플레이 1,804시간 · /)
    expect(playTime.parentElement).not.toHaveTextContent('경기')
    expect(within(profile).getByText('237')).toBeInTheDocument()
    /* WIN RATE — 현재 시즌 랭크 승리 / 시즌 판수 (300 / 400). 분석 경기 승률(62.5%)이 아니다 */
    const wr = within(profile).getByText('WIN RATE').parentElement!
    expect(wr).toHaveTextContent('75.0%')
    expect(wr).not.toHaveTextContent('62.5%')
    /* 5칸 증감 배지 — 최근 N판 vs 그 이전 판. 승률만 %p(변화율 −19.03%를 승률 차로), 나머지는 변화율 % */
    expect(within(profile).getByText('−12.4%p')).toBeInTheDocument()
    expect(within(profile).queryByText('−19.0%')).not.toBeInTheDocument()
    expect(within(profile).getByText('+0.5%')).toBeInTheDocument()
    expect(within(profile).getByText('+16.7%')).toBeInTheDocument()
    expect(within(profile).getByText('+23.6%')).toBeInTheDocument()
    expect(within(profile).getByText('+17.0%')).toBeInTheDocument()
    expect(within(profile).getByText('값 아래 증감은 최근 26판을 그 이전 경기와 비교한 값입니다 — 승률은 승률 차(%p), 나머지는 평균 변화율(%).')).toBeInTheDocument()

    fireEvent.error(img)
    expect(photo()).toBeNull()
  })

  it('GAMES 줄은 AI 총평 아래 — 분석한 경기 수와 수집했지만 뺀 경기 수(정제 제외 + 버린 기록). 헤더·프로필에서는 지운다', async () => {
    routeFetch({ stats: [() => json({ ...STATS, chapters: { excluded_matches: 5, dropped_records: 1 } })], comment: [() => json(COMMENT)] })
    setup()
    await findSummary()
    const readout = screen.getByRole('region', { name: 'AI READOUT' })
    const games = within(readout).getByText('GAMES').parentElement!
    expect(games).toHaveTextContent('GAMES120분석한 최근 경기 · 수집 126판 중 6판 제외')
    expect(games).toHaveAttribute('title', expect.stringContaining('실격·무효'))
    expect(within(screen.getByRole('region', { name: 'PROFILE' })).queryByText('GAMES')).not.toBeInTheDocument()
    expect(screen.queryByText(/최근 120경기 ·/)).not.toBeInTheDocument()
    cleanup()

    /* 제외 판수가 안 오면 경기 수만. 코멘트가 오는 중에도 보인다 */
    routeFetch({ stats: [() => json({ ...STATS, chapters: undefined })], comment: [pending] })
    setup()
    const loading = await screen.findByRole('region', { name: 'AI READOUT' })
    expect(within(loading).getByText('GAMES').parentElement).toHaveTextContent(/^GAMES120분석한 최근 경기$/)
  })

  it('유저 정보 값이 빠지면 지어내지 않는다 — 레벨·국가 없음, APM 등은 —', async () => {
    const { window_delta: _w, avatar_url: _a, xp: _x, country: _c, joined_at: _j, play_time_seconds: _t, friend_count: _f, apm: _p, pps: _s, vs: _v, ...rest } = STATS.profile
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
    expect(screen.queryByText(/^값 아래 증감은/)).not.toBeInTheDocument()
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
    expect(within(panel).getByText('RANK').nextElementSibling).toHaveTextContent(/^\?/)
    expect(within(panel).getAllByText('—')).toHaveLength(2)
    expect(within(panel).queryByText(/편차/)).not.toBeInTheDocument()
    expect(screen.queryByText(/-1/)).not.toBeInTheDocument()
    expect(screen.queryByText(/플레이 -?\d/)).not.toBeInTheDocument()
  })

  it("랭크 'z'(언랭크)는 '?'로 쓰고, 표본 부족으로 보지 않아 TR·분석을 그대로 보여준다 (수집 명세 6.1)", async () => {
    /* 실측 사례 — 28판, rd 115.2, tr 19702.18 */
    const profile = { ...STATS.profile, rank: 'z', tr: 19702.18, rd: 115.2 }
    const { urls } = routeFetch({ stats: [() => json({ ...STATS, match_count: 28, profile })], comment: [pending] })
    setup()

    const panel = await screen.findByRole('region', { name: 'PROFILE' })
    const rank = within(panel).getByText('RANK').nextElementSibling!
    expect(rank).toHaveTextContent(/^\?/)
    expect(rank).toHaveAttribute('title', expect.stringMatching(/^언랭크/))
    expect(within(panel).getByText('19,702')).toBeInTheDocument()
    /* 분석은 그대로 — 라이트 코멘트를 부른다 */
    await waitFor(() => expect(urls().some((u) => u.includes('/comment/'))).toBe(true))
  })

  it('랭크 값이 안 오면 랭크가 없다고 단정하지 않고 —로 둔다', async () => {
    const { rank: _rank, ...profile } = STATS.profile
    routeFetch({ stats: [() => json({ ...STATS, profile })], comment: [pending] })
    setup()

    const panel = await screen.findByRole('region', { name: 'PROFILE' })
    expect(within(panel).queryByTitle(/언랭크/)).not.toBeInTheDocument()
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

  it('TR 추이는 경기 후 TR — 마지막 점이 지금 TR이고, 언랭크(-1)면 받은 그대로', async () => {
    /* 매치 전 TR [24000, 24100, 24050, 24321.6] + 지금 TR 24400 → 경기 후 TR [24100, 24050, 24321.6, 24400] */
    routeFetch({ stats: [() => json({ ...STATS, profile: { ...STATS.profile, tr: 24400 } })], comment: [pending] })
    setup()
    const card = await screen.findByRole('region', { name: 'TR 추이' })
    const stat = (k: string) => within(card).getByText(k).parentElement!
    expect(stat('구간 최고')).toHaveTextContent('24,400.0')
    expect(stat('구간 최저')).toHaveTextContent('24,050.0')
    expect(stat('전체 변동')).toHaveTextContent('+300.0')
  })

  it('TR 추이의 ΔTR 알약을 누르면 헤비 01장과 같은 설명이 펼쳐지고, Esc로 카드만 닫힌다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { user, onBack } = setup()
    const card = await screen.findByRole('region', { name: 'TR 추이' })

    /* AI 코멘트와 같은 백엔드 ΔTR (헤비 01장과 같은 값) */
    await user.click(within(card).getByRole('button', { name: 'ΔTR +12.4 TR 설명' }))
    const note = within(card).getByRole('note')
    expect(note).toHaveTextContent('ΔTR · TR 흐름+12.4 TR ▲')
    expect(note).toHaveTextContent('최근 평균 TR − 전체 평균 TR')

    await user.keyboard('{Escape}')
    expect(within(card).queryByRole('note')).not.toBeInTheDocument()
    expect(onBack).not.toHaveBeenCalled()
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

  it('시즌 전적이 없으면(이번 시즌 0판) NaN 대신 —로 보여준다', async () => {
    const profile0 = { ...COLD.profile, season_games_played: undefined, season_wins: undefined, season_losses: undefined, season_win_rate: undefined }
    routeFetch({ stats: [() => json({ ...COLD, match_count: 4, profile: profile0, fixed_metrics: { tr_trend: [], recent_form: [] } })] })
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
    expect(await findSummary()).toBeInTheDocument()
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
    expect(screen.getByText('300승 100패')).toBeInTheDocument()

    resolve(new Response(JSON.stringify({ ...STATS, match_count: 200 }), { status: 200 }))
    /* 새 결과의 경기 수 — AI READOUT 아래 GAMES 줄 */
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'AI READOUT' })).getByText('GAMES').parentElement).toHaveTextContent('GAMES200'))
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

  it('헤비에 갔다 라이트로 돌아와도 이미 받은 라이트 코멘트는 다시 부르지 않는다 (FR-06)', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { urls } = routeFetch({ stats: [() => json(STATS)], comment: [() => json(COMMENT)] })
    const { user } = setup()
    expect(await findSummary()).toBeInTheDocument()

    await user.keyboard('h')
    await user.keyboard('l')
    expect(await findSummary()).toBeInTheDocument()
    expect(urls().filter((u) => u.includes('scope=light'))).toHaveLength(1)
  })

  it('코멘트를 받는 도중에 뷰를 바꿔도 요청을 끊지 않는다 — 돌아와서 다시 부르면 LLM이 새로 만들어 내용이 바뀐다 (QA BUG-02)', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    FakeEventSource.all = []
    let answer: (r: Response) => void = () => {}
    const { fn, urls } = routeFetch({ stats: [() => json(STATS)], comment: [() => new Promise<Response>((res) => (answer = res))] })
    const { user } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })

    /* 라이트 코멘트가 오는 중에 헤비로 — 헤비 스트림이 열린다 */
    await user.keyboard('h')
    const lightCall = fn.mock.calls.find(([u]) => String(u).includes('scope=light'))!
    expect((lightCall[1] as RequestInit).signal!.aborted).toBe(false)
    const stream = FakeEventSource.last

    /* 헤비 스트림이 도는 중에 라이트로 — 스트림도 끊지 않는다 */
    await user.keyboard('l')
    expect(stream.closed).toBe(false)
    answer(new Response(JSON.stringify(COMMENT), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    expect(await findSummary()).toBeInTheDocument()

    await user.keyboard('h')
    await user.keyboard('l')
    expect(urls().filter((u) => u.includes('scope=light'))).toHaveLength(1)
    expect(FakeEventSource.all.filter((s) => s.url.includes('scope=heavy'))).toHaveLength(1)
  })

  it('하이라이트를 못 불러오면 위 AI READOUT의 다시 시도 버튼을 가리킨다 (QA 개선-01)', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [() => json({ error_code: 'LLM_FORMAT_ERROR', message: 'x' }, 502)] })
    setup()
    expect(await screen.findByText('하이라이트를 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.getByText(/AI READOUT 칸의 \[다시 시도\]/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '다시 시도' })).toHaveLength(1)
  })

  it('이어지는 하이라이트가 하나도 없으면 생성 실패로 안내하고 AI 총평만 남긴다 (기능 명세 3.3절)', async () => {
    const comment = { ...COMMENT, highlights: [{ stat: 'tr_trend_delta', sentence: '후보가 아님' }] }
    routeFetch({ stats: [() => json(STATS)], comment: [() => json(comment)] })
    setup()

    expect(await screen.findByText('하이라이트를 생성하지 못했습니다.')).toBeInTheDocument()
    expect(await findSummary()).toBeInTheDocument()
    expect(screen.queryByText('후보가 아님')).not.toBeInTheDocument()
  })

  it('ESC로 처음 화면으로 돌아간다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { user, onBack } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })
    await user.keyboard('{Escape}')
    expect(onBack).toHaveBeenCalledOnce()
  })
})
