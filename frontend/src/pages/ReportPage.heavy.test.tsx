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
    attack: { delta_app: 0.092, delta_weighted_app: 0.045, my_avg: { app: 0.742, weighted_app: 0.688 }, opp_avg: { app: 0.65, weighted_app: 0.643 } },
    defense: { delta_vs_apm: -0.055, delta_cheese_index: -16.2, my_avg: { vs_apm: 1.967, cheese_index: -23.2 }, opp_avg: { vs_apm: 2.022, cheese_index: -7.0 } },
    strength_split: -0.2,
    strength_quintiles: [
      { quintile: 1, matches: 5, wins: 4, win_rate: 0.8 },
      { quintile: 2, matches: 5, wins: 3, win_rate: 0.6 },
      { quintile: 3, matches: 5, wins: 3, win_rate: 0.6 },
      { quintile: 4, matches: 5, wins: 2, win_rate: 0.4 },
      { quintile: 5, matches: 5, wins: 3, win_rate: 0.6 },
    ],
    tr_trend_basis: { recent_matches: 3, total_matches: 4, recent_avg_tr: 24157.2, overall_avg_tr: 24117.9 },
    comeback_rate: 0.556,
    comeback_rate_against: 0.25,
    delta_comeback: 0.306,
    comeback_samples: { comeback_opportunities: 9, comeback_won: 5, comeback_against_opportunities: 8, comeback_against_allowed: 2 },
    session_vs_slope: -0.12,
  },
  round_curves: { pps: [2.4, 2.38, 2.35], vs: [2.1, 2.0, 1.9], samples: [25, 25, 18] },
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
    /* 자세히 보기는 큰 차트가 있는 챕터만 — 01 · 02 · 05(5구간이 왔을 때) · 07 */
    expect(screen.getAllByRole('button', { name: '자세히 보기' })).toHaveLength(4)

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
    /* ΔTR이 쓰는 최근 N판(tr_trend_basis.recent_matches)을 초록 구간으로 */
    expect(within(tr).getAllByText('최근 3판 (ΔTR 기준)').length).toBeGreaterThan(0)
    /* 근거는 전체 평균만 — 최근·차이는 위쪽 ΔTR(백엔드 값)과 기준이 달라 두지 않는다 */
    expect(within(tr).getByText('Avg of 4g')).toBeInTheDocument()
    expect(within(tr).queryByText(/^Recent/)).not.toBeInTheDocument()
    expect(within(tr).queryByText('Gap')).not.toBeInTheDocument()

    const ps = chapter('플레이스타일 상대비교')
    expect(within(ps).getByRole('img', { name: '상대 대비 편차' })).toBeInTheDocument()
    expect(within(ps).getByText('+0.08')).toBeInTheDocument()
    expect(within(ps).getByText('−0.02')).toBeInTheDocument()
    /* 계산되지 않은 Inf DS는 칩에서 뺀다 */
    expect(within(ps).queryByText('Inf DS', { selector: 'span' })).not.toBeInTheDocument()

    expect(within(chapter('공격 효율')).getByText('+0.092', { selector: 'b' })).toBeInTheDocument()
    /* 나 vs 상대 평균 막대 — 음수(Cheese Index)는 0선에서 왼쪽으로 */
    const atkBars = within(chapter('공격 효율')).getByRole('img', { name: '공격 효율 — 나 대 상대 평균' })
    expect(atkBars).toHaveTextContent('0.742')
    expect(atkBars).toHaveTextContent('0.650')
    const defBars = within(chapter('수비 · 가비지 처리')).getByRole('img', { name: '수비 · 가비지 처리 — 나 대 상대 평균' })
    expect(defBars).toHaveTextContent('−23.2')
    expect(defBars).toHaveTextContent('−7.0')
    const split = chapter('상대 강도별 승률')
    expect(within(split).getByText('−20.0%p')).toBeInTheDocument()
    /* 5구간 승률 막대 — Q1(약한 상대) → Q5(강한 상대) */
    expect(within(split).getByRole('img', { name: '상대와의 TR 차이 구간별 승률' })).toBeInTheDocument()
    expect(within(split).getByText('Q1 · 약한 상대')).toBeInTheDocument()
    expect(within(split).getByText('Q5 · 강한 상대')).toBeInTheDocument()
    expect(within(split).getByText('80.0%')).toBeInTheDocument()

    const cb = chapter('역전승 퍼포먼스')
    expect(within(cb).getByRole('img', { name: '역전승률과 역전 허용률 비교' })).toBeInTheDocument()
    expect(within(cb).getByText('+30.6%p')).toBeInTheDocument()
    expect(within(cb).getByText('불리한 경기 중 역전승한 비율 · 9번 중 5번')).toBeInTheDocument()
    expect(within(cb).getByText('유리한 경기 중 역전패한 비율 · 8번 중 2번')).toBeInTheDocument()

    const cond = chapter('경기 내 컨디션 변화')
    expect(within(cond).getByRole('img', { name: '라운드별 평균 VS' })).toBeInTheDocument()
    expect(within(cond).getByText('R3 평균 VS')).toBeInTheDocument()
    expect(within(cond).getByText('−0.05')).toBeInTheDocument()
  })

  it('03·04·06·07장 머리 증감 알약도 누르면 무슨 값인지 펼친다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    /* 알약 버튼 이름에는 값이 같이 들어간다 — 칸의 ? 버튼과 구분 */
    const cases: [string, string, string][] = [
      /* ▲▼ 기준 — 무엇과 무엇을 비교했는지. 03·04는 같은 챕터 다른 지표를 '함께' 줄로 */
      ['공격 효율', 'ΔAPP +0.092 설명', '같은 경기에서 만난 상대의 APP'],
      ['수비 · 가비지 처리', 'ΔVS/APM −0.055 설명', '함께ΔCheese Index −16.2 ▼ · 상대보다 공격적'],
      ['역전승 퍼포먼스', 'ΔComeback +30.6%p 설명', '역전승률 − 역전 허용률 (%p)'],
      ['경기 내 컨디션 변화', 'VS Slope −0.12/R 설명', '그 평균들에 맞춘 직선의 기울기 = 라운드당 VS 변화'],
    ]
    for (const [title, name, text] of cases) {
      const ch = chapter(title)
      await user.click(within(ch).getByRole('button', { name }))
      expect(within(ch).getByRole('note')).toHaveTextContent(text)
      /* 머리 — 무슨 지표인지와 ▲·▼의 뜻 */
      if (title === '공격 효율') expect(within(ch).getByRole('note')).toHaveTextContent('ΔAPP · 공격 효율+0.092 ▲상대보다 블록당 공격이 많음')
      await user.keyboard('{Escape}')
    }
  })

  it('01장 ΔTR 알약을 누르면 무슨 값인지 펼치고, Esc로 닫힌다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    const tr = chapter('TR · 능력치 추이')
    const pill = within(tr).getByRole('button', { name: 'ΔTR +12.4 TR 설명' })
    expect(pill).toHaveTextContent('+12.4 TR')
    await user.click(pill)
    expect(pill).toHaveAttribute('aria-expanded', 'true')
    const note = within(tr).getByRole('note')
    expect(note).toHaveTextContent('최근 평균 TR − 전체 평균 TR')
    /* 백엔드가 준 최근·전체 판수(tr_trend_basis)로 */
    expect(note).toHaveTextContent('최신 3판 — TR이 있는 경기의 30% (최소 3판 · 최대 30판)')
    expect(note).toHaveTextContent('4판 — 최근 1년')
    await user.keyboard('{Escape}')
    expect(within(tr).queryByRole('note')).not.toBeInTheDocument()
  })

  it('역전승률이 0%면 칸에 ▲를 붙이지 않는다', async () => {
    routeFetch({ stats: [() => json({ ...FULL, delta_metrics: { ...FULL.delta_metrics, comeback_rate: 0, delta_comeback: -0.25 } })], comment: [pending] })
    setup()
    await ready()

    const cb = chapter('역전승 퍼포먼스')
    const rate = within(cb).getByText('Comeback Rate').parentElement!
    expect(rate).toHaveTextContent('0.0%')
    expect(rate).not.toHaveTextContent('▲')
    expect(within(cb).getByText('Comeback Allowed').parentElement).toHaveTextContent('25.0% ▼')
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
    /* 역전 허용 기회도 경기 형식별 — 7선승은 4점 차부터 */
    const lead = within(within(cb).getByRole('note')).getByRole('table', { name: '역전 허용 기회 기준' })
    expect(within(lead).getByRole('row', { name: '7선승 4판+' })).toBeInTheDocument()
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

  it('03·04장 칸마다 ? 버튼이 있고, 누르면 식과 비교 방법을 펼친다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    const atk = chapter('공격 효율')
    /* 칸 ? 버튼 2개 + 머리 알약 1개 */
    expect(within(atk).getAllByRole('button', { name: /설명$/ })).toHaveLength(3)
    await user.click(within(atk).getByRole('button', { name: 'ΔAPP 설명' }))
    expect(within(atk).getByRole('note')).toHaveTextContent('APM ÷ (PPS × 60)')
    await user.keyboard('{Escape}')

    const def = chapter('수비 · 가비지 처리')
    expect(within(def).getAllByRole('button', { name: /설명$/ })).toHaveLength(3)
    await user.click(within(def).getByRole('button', { name: 'ΔCheese Index 설명' }))
    expect(within(def).getByRole('note')).toHaveTextContent('방어 성향')
  })

  it('05장 Strength Split 칸의 ? 버튼은 구간 나누는 법과 계산식을 펼친다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    const split = chapter('상대 강도별 승률')
    await user.click(within(split).getByRole('button', { name: 'Strength Split 설명' }))
    const note = within(split).getByRole('note')
    expect(within(note).getByRole('row', { name: 'Q5 가장 강한 상대 20%' })).toBeInTheDocument()
    expect(note).toHaveTextContent('Q5 승률 − Q1 승률')
    await user.keyboard('{Escape}')
    expect(within(split).queryByRole('note')).not.toBeInTheDocument()
  })

  it('05장 자세히 보기는 5구간 승률 표를 띄운다', async () => {
    routeFetch({ stats: [() => json(FULL)], comment: [pending] })
    const { user } = setup()
    await ready()

    await user.click(within(chapter('상대 강도별 승률')).getByRole('button', { name: '자세히 보기' }))
    const dialog = screen.getByRole('dialog', { name: '상대 강도별 승률 상세' })
    expect(within(dialog).getByRole('img', { name: '상대와의 TR 차이 구간별 승률' })).toBeInTheDocument()
    /* 머리글 + 5구간 */
    const rows = within(dialog).getAllByRole('row')
    expect(rows).toHaveLength(6)
    expect(rows[1]).toHaveTextContent('Q1 · 약한 상대54')
    expect(rows[1]).toHaveTextContent('80.0%')
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
