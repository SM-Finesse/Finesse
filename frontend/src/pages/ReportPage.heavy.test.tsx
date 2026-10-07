import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StatsResponse } from '../api/types'
import { LangProvider } from '../i18n/LangProvider'
import { COLD, FakeEventSource, json, pending, routeFetch, STATS } from '../test/fixtures'
import type { View } from '../types'
import { ReportPage } from './ReportPage'

const FULL: StatsResponse = {
  ...STATS,
  delta_metrics: {
    ...STATS.delta_metrics,
    playstyle_relative: { delta_opener: 0.084, delta_plonk: -0.021, delta_stride: 0.057 },
    strength_split: -0.2,
    comeback_rate: 0.556,
    comeback_rate_against: 0.25,
    delta_comeback: 0.306,
    comeback_samples: { comeback_opportunities: 9, comeback_won: 5, comeback_against_opportunities: 8, comeback_against_allowed: 2 },
    session_vs_slope: -0.12,
  },
  round_curves: { pps: [2.4, 2.38, 2.35], vs: [2.1, 2.0, 1.9] },
  rivals: {
    items: [
      { nickname_masked: 'zz***', matches: 3, wins: 1, losses: 2 },
      { nickname_masked: 'ab***', matches: 6, wins: 4, losses: 2, last_match_at: '2026-09-30T10:00:00Z' },
    ],
    page: 1,
    page_size: 20,
    total: 2,
  },
}

const TITLES = ['TR · 능력치 추이', '플레이스타일 상대비교', '공격 효율', '수비 · 가비지 처리', '상대 강도별 승률', '역전승 퍼포먼스', '경기 내 컨디션 변화', '자주 만난 상대']

beforeEach(() => {
  FakeEventSource.all = []
  vi.stubGlobal('EventSource', FakeEventSource)
})
afterEach(() => {
  vi.unstubAllGlobals()
})

function setup(initialView: View = 'heavy') {
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

/** 리포트가 그려지고 챕터 스트림까지 열릴 때까지 — 스트림은 effect에서 열려 화면보다 한 박자 늦을 수 있다 */
async function ready() {
  await screen.findByRole('region', { name: 'PROFILE' })
  await waitFor(() => expect(FakeEventSource.all).toHaveLength(1))
}

const chapter = (name: string) => screen.getByRole('region', { name })
const send = (id: string, status: 'ok' | 'failed' | 'timeout', footnote?: string) =>
  act(() => FakeEventSource.last.emit('chapter', JSON.stringify({ chapter_id: id, status, footnote })))
const done = () => act(() => FakeEventSource.last.emit('done', '{}'))

describe('ReportPage — 헤비 뷰', () => {
  it('8개 챕터를 순서대로 그리고, 챕터 코멘트 스트림을 한 번만 연다', async () => {
    const { urls } = routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    setup()
    await ready()

    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(TITLES)
    /* 자세히 보기는 큰 차트가 있는 챕터만 — 01 · 02 · 07 */
    expect(screen.getAllByRole('button', { name: '자세히 보기' })).toHaveLength(3)

    expect(FakeEventSource.all).toHaveLength(1)
    expect(FakeEventSource.last.url).toBe('/api/v1/comment/ExamplePlayer?scope=heavy')
    /* 라이트 코멘트는 부르지 않는다 */
    expect(urls().filter((u) => u.includes('/comment/'))).toHaveLength(0)
  })

  it('코멘트는 도착하는 대로 그 챕터에 채우고, 실패·시간 초과는 챕터마다 따로 알린다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    setup()
    await ready()
    expect(within(chapter('TR · 능력치 추이')).getByRole('status', { name: '코멘트를 생성하는 중입니다 · 챕터별 개별 호출' })).toBeInTheDocument()

    send('tr_trend', 'ok', '최근 폼이 평균보다 좋습니다.')
    send('attack', 'timeout')
    send('defense', 'failed')
    expect(within(chapter('TR · 능력치 추이')).getByText('최근 폼이 평균보다 좋습니다.')).toBeInTheDocument()
    expect(within(chapter('공격 효율')).getByText('시간 안에 이 챕터의 코멘트를 받지 못했습니다.')).toBeInTheDocument()
    expect(within(chapter('수비 · 가비지 처리')).getByText('이 챕터의 코멘트를 만들지 못했습니다.')).toBeInTheDocument()

    done()
    expect(FakeEventSource.last.closed).toBe(true)
    /* done까지 오지 않은 챕터는 실패로 */
    expect(within(chapter('역전승 퍼포먼스')).getByText('이 챕터의 코멘트를 만들지 못했습니다.')).toBeInTheDocument()
  })

  it('챕터마다 근거 수치를 차트·스코어 칸·근거 칩으로 보여준다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    setup()
    await ready()
    /* 근거 칩은 코멘트가 정해진 뒤에 붙는다 */
    done()

    const tr = chapter('TR · 능력치 추이')
    expect(within(tr).getByRole('img', { name: 'TR 추이' })).toBeInTheDocument()
    expect(within(tr).getByText('Recent 1g')).toBeInTheDocument()
    expect(within(tr).getByText('Avg of 4g')).toBeInTheDocument()

    const ps = chapter('플레이스타일 상대비교')
    expect(within(ps).getByRole('img', { name: '상대 대비 편차' })).toBeInTheDocument()
    expect(within(ps).getByText('+0.08')).toBeInTheDocument()
    expect(within(ps).getByText('−0.02')).toBeInTheDocument()
    /* 계산되지 않은 Inf DS는 칩에서 뺀다 */
    expect(within(ps).queryByText('Inf DS', { selector: 'span' })).not.toBeInTheDocument()

    expect(within(chapter('공격 효율')).getByText('+0.092', { selector: 'b' })).toBeInTheDocument()
    expect(within(chapter('상대 강도별 승률')).getByText('−20.0%p')).toBeInTheDocument()

    const cb = chapter('역전승 퍼포먼스')
    expect(within(cb).getByRole('img', { name: '역전승률과 역전 허용률 비교' })).toBeInTheDocument()
    expect(within(cb).getByText('+30.6%p')).toBeInTheDocument()
    expect(within(cb).getByText('패배 중 역전승한 비율 · 9번 중 5번')).toBeInTheDocument()
    expect(within(cb).getByText('승리 중 역전패한 비율 · 8번 중 2번')).toBeInTheDocument()

    const cond = chapter('경기 내 컨디션 변화')
    expect(within(cond).getByRole('img', { name: '라운드별 평균 VS' })).toBeInTheDocument()
    expect(within(cond).getByText('R3 평균 VS')).toBeInTheDocument()
    expect(within(cond).getByText('−0.05')).toBeInTheDocument()
  })

  it('역전승 칸의 ? 버튼은 값이 뭔지 펼쳐 보이고, 다시 누르거나 Esc로 닫힌다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    const cb = chapter('역전승 퍼포먼스')
    const btn = within(cb).getByRole('button', { name: 'Comeback Rate 설명' })
    expect(btn).toHaveAttribute('aria-expanded', 'false')

    await user.click(btn)
    expect(btn).toHaveAttribute('aria-expanded', 'true')
    const note = within(cb).getByRole('note')
    expect(note).toHaveTextContent('크게 뒤진 경기를 끝내 이긴 비율')
    /* 경기 형식마다 달라지는 역전 기회 기준은 표로, 대상 경기 범위까지 */
    const gaps = within(note).getByRole('table', { name: '역전 기회 기준' })
    expect(within(gaps).getByRole('row', { name: '5선승 3판+' })).toBeInTheDocument()
    expect(within(gaps).getByRole('row', { name: '7선승 4판+' })).toBeInTheDocument()
    expect(note).toHaveTextContent('최근 1년 · 최대 300판')
    await user.click(btn)
    expect(within(cb).queryByRole('note')).not.toBeInTheDocument()

    await user.click(within(cb).getByRole('button', { name: 'Comeback Allowed 설명' }))
    expect(within(within(cb).getByRole('note')).getByRole('table', { name: '역전 허용 기회 기준' })).toHaveTextContent('형식과 무관')
    await user.keyboard('{Escape}')
    expect(within(cb).queryByRole('note')).not.toBeInTheDocument()
  })

  it('자주 만난 상대 — 조우 횟수순 순위표, 우세·천적 타일. 반복 조우가 적어도 표본 안내를 따로 띄우지 않는다 (기능 명세 3.6절)', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    setup()
    await ready()
    send('rivals', 'ok', '아직 상성을 말하기 이릅니다.')

    const rv = chapter('자주 만난 상대')
    const rows = within(within(rv).getByRole('table')).getAllByRole('row')
    /* 머리글 + 2명, 많이 만난 상대가 위 */
    expect(rows).toHaveLength(3)
    expect(rows[1]).toHaveTextContent('ab***')
    expect(rows[1]).toHaveTextContent('우세')
    expect(within(rv).getByText('4승 2패 · 67%')).toBeInTheDocument()
    expect(within(rv).getByText('2명 · 20명/페이지')).toBeInTheDocument()
    /* 5경기 이상 만난 상대가 5명이 안 되지만, 10판 이상이면 표본 안내를 따로 붙이지 않는다 */
    expect(within(rv).queryByRole('img', { name: '주의' })).not.toBeInTheDocument()
    expect(within(rv).getByText('아직 상성을 말하기 이릅니다.')).toBeInTheDocument()
    expect(within(rv).getByText('상대 데이터 취급 방식')).toBeInTheDocument()
  })

  it('순위표는 20명씩 나눠 보여준다', async () => {
    const items = Array.from({ length: 25 }, (_, i) => ({ nickname_masked: `p${i}***`, matches: 30 - i, wins: 10, losses: 20 - i }))
    routeFetch({ stats: [() => json({ ...FULL, rivals: { items, page: 1, page_size: 20, total: 25 } })], comment: [pending] })
    const { user } = setup()
    await ready()

    const rv = chapter('자주 만난 상대')
    expect(within(within(rv).getByRole('table')).getAllByRole('row')).toHaveLength(21)
    expect(within(rv).getByText('25명 중 1–20명 표시 · 조우 횟수 내림차순')).toBeInTheDocument()
    await user.click(within(rv).getByRole('button', { name: '다음 페이지' }))
    expect(within(within(rv).getByRole('table')).getAllByRole('row')).toHaveLength(6)
    expect(within(rv).getByRole('button', { name: '2' })).toHaveAttribute('aria-current', 'page')
  })

  it('20명이 넘는 상대(139명) — 7페이지, 페이지 사이는 …, 순번은 이어지고, 타일은 전체에서 고른다', async () => {
    /* 프로토타입 스크린샷과 같은 139명. 받은 순서를 뒤집어 정렬을 프론트가 하는지도 본다 */
    const items = Array.from({ length: 139 }, (_, i) => {
      const matches = 40 - Math.floor(i / 4)
      /* 101번째(6페이지)는 한 판도 못 이긴 상대 — 천적 타일은 지금 페이지가 아니라 전체에서 고른다 */
      const wins = i === 100 ? 0 : Math.round(matches * 0.6)
      return { nickname_masked: `r${String(i).padStart(3, '0')}**`, matches, wins, losses: matches - wins, last_match_at: `2026-09-${String(28 - (i % 4)).padStart(2, '0')}T00:00:00Z` }
    }).reverse()
    routeFetch({ stats: [() => json({ ...FULL, rivals: { items, page: 1, page_size: 20, total: 139 } })], comment: [pending] })
    const { user } = setup()
    await ready()

    const rv = chapter('자주 만난 상대')
    const rows = () => within(within(rv).getByRole('table')).getAllByRole('row').slice(1)
    const firstCell = (row: HTMLElement) => within(row).getAllByRole('cell')[0].textContent
    const pages = () => within(within(rv).getByRole('navigation', { name: 'pages' })).getAllByRole('button').map((b) => b.textContent)

    /* 1페이지 — 조우 40번이 넷(r000~r003), 같으면 최근에 대전한 상대가 위 */
    expect(within(rv).getByText('139명 · 20명/페이지')).toBeInTheDocument()
    expect(rows()).toHaveLength(20)
    expect(rows().slice(0, 4).map((r) => r.textContent)).toEqual([expect.stringContaining('r000**'), expect.stringContaining('r001**'), expect.stringContaining('r002**'), expect.stringContaining('r003**')])
    expect(firstCell(rows()[0])).toBe('1')
    expect(firstCell(rows()[19])).toBe('20')
    expect(pages()).toEqual(['‹', '1', '2', '7', '›'])
    expect(within(rv).getByText('…')).toBeInTheDocument()
    expect(within(rv).getByRole('button', { name: '이전 페이지' })).toBeDisabled()
    expect(within(rv).getByText('139명 중 1–20명 표시 · 조우 횟수 내림차순')).toBeInTheDocument()
    /* 천적 타일 — 6페이지에 있는 r100**(0승) */
    expect(within(rv).getByText('0승 15패 · 0%')).toBeInTheDocument()
    expect(within(rv).getAllByText('r100**').length).toBeGreaterThan(0)

    /* 마지막 페이지 — 121~139번, 19명 */
    await user.click(within(rv).getByRole('button', { name: '7' }))
    expect(rows()).toHaveLength(19)
    expect(firstCell(rows()[0])).toBe('121')
    expect(firstCell(rows()[18])).toBe('139')
    expect(within(rv).getByRole('button', { name: '다음 페이지' })).toBeDisabled()
    expect(within(rv).getByText('139명 중 121–139명 표시 · 조우 횟수 내림차순')).toBeInTheDocument()

    /* 가운데 페이지 — 양쪽에 … (1 … 3 4 5 … 7) */
    await user.click(within(rv).getByRole('button', { name: '이전 페이지' }))
    await user.click(within(rv).getByRole('button', { name: '5' }))
    await user.click(within(rv).getByRole('button', { name: '4' }))
    expect(pages()).toEqual(['‹', '1', '3', '4', '5', '7', '›'])
    expect(within(rv).getAllByText('…')).toHaveLength(2)
    expect(within(rv).getByRole('button', { name: '4' })).toHaveAttribute('aria-current', 'page')
    expect(firstCell(rows()[0])).toBe('61')
  })

  it('자세히 보기는 큰 차트와 표를 띄우고, ESC는 리포트를 떠나지 않고 창만 닫는다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user, onBack } = setup()
    await ready()

    await user.click(within(chapter('TR · 능력치 추이')).getByRole('button', { name: '자세히 보기' }))
    const dialog = screen.getByRole('dialog', { name: 'TR · 능력치 추이 상세' })
    /* 머리글 + 경기 4개 */
    expect(within(dialog).getAllByRole('row')).toHaveLength(5)
    expect(within(dialog).getByRole('button', { name: '닫기' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(onBack).not.toHaveBeenCalled()
  })

  it('계산되지 않은 챕터는 값을 지어내지 않고 데이터 없음으로 안내한다', async () => {
    const empty = { ...STATS, delta_metrics: { tr_trend_delta: 1.5 }, fixed_metrics: { ...STATS.fixed_metrics, tr_trend: [] } }
    routeFetch({ stats: [() => json(empty)], comment: [pending] })
    setup()
    await ready()

    expect(within(chapter('TR · 능력치 추이')).getByText('TR 추이 데이터를 받지 못해 ΔTR만 보여줍니다.')).toBeInTheDocument()
    for (const name of ['플레이스타일 상대비교', '공격 효율', '수비 · 가비지 처리', '상대 강도별 승률', '역전승 퍼포먼스', '경기 내 컨디션 변화']) {
      expect(within(chapter(name)).getByText('이 챕터를 계산할 데이터가 아직 없습니다.')).toBeInTheDocument()
    }
    expect(within(chapter('자주 만난 상대')).getByText('같은 상대를 만난 기록이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '자세히 보기' })).not.toBeInTheDocument()
  })

  it('스트림이 끊기면 받지 못한 챕터는 실패로 두고, 다시 시도하면 새로 연다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()
    send('tr_trend', 'ok', '각주')

    act(() => FakeEventSource.last.emit('error'))
    expect(screen.getByRole('alert')).toHaveTextContent('챕터 코멘트 연결이 끊겼습니다.')
    expect(within(chapter('플레이스타일 상대비교')).getByText('이 챕터의 코멘트를 만들지 못했습니다.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(FakeEventSource.all).toHaveLength(2))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('다 받은 뒤 라이트↔헤비를 오가도 스트림을 다시 열지 않는다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()
    send('tr_trend', 'ok', '첫 각주')
    done()

    await user.keyboard('l')
    await user.keyboard('h')
    expect(FakeEventSource.all).toHaveLength(1)
    expect(screen.getByText('첫 각주')).toBeInTheDocument()
  })

  it('표본 부족이면 LLM을 부르지 않고, 잠긴 8개 챕터와 이유만 보여준다', async () => {
    routeFetch({ stats: [() => json(COLD)], comment: [pending] })
    const { user } = setup()
    /* 라이트와 같은 UNLOCK 미터 — 문구만 헤비 기준 */
    const meter = await screen.findByRole('region', { name: '분석이 열리기까지' })
    expect(within(meter).getByText('3')).toBeInTheDocument()
    expect(within(meter).getByText(/헤비 8개 챕터와 챕터별 AI 코멘트가 열립니다/)).toBeInTheDocument()

    const locked = screen.getByRole('region', { name: '최근 1년 랭크 경기 10판이 쌓이면 열리는 챕터' })
    expect(within(locked).getAllByRole('listitem')).toHaveLength(8)
    expect(within(locked).getAllByText('상대 비교 필요')).toHaveLength(6)
    expect(within(locked).getAllByText('표본 부족')).toHaveLength(2)
    expect(FakeEventSource.all).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: '라이트 뷰로 보기' }))
    expect(screen.queryByRole('region', { name: '최근 1년 랭크 경기 10판이 쌓이면 열리는 챕터' })).not.toBeInTheDocument()
  })
})
