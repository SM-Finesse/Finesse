import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LangProvider } from '../i18n/LangProvider'
import { COLD, COMMENT, json, pending, routeFetch, STATS } from '../test/fixtures'
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
    expect(within(profile).getByText('X')).toBeInTheDocument()
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

  it('최근 경기 승패 보드와 TR 추이를 보여준다', async () => {
    routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    setup()

    const board = await screen.findByRole('img', { name: '최근 10경기' })
    expect(board.querySelectorAll('[data-result="W"]')).toHaveLength(7)
    expect(board.querySelectorAll('[data-result="L"]')).toHaveLength(3)
    expect(screen.getByTestId('tr-chart')).toBeInTheDocument()
    expect(screen.getByText('구간 최고')).toBeInTheDocument()
  })

  it('콜드스타트면 LLM을 부르지 않고 데이터 부족 안내를 띄운다', async () => {
    const { urls } = routeFetch({ stats: [() => json(COLD)] })
    setup()

    expect(await screen.findByText('최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByText('추이를 그릴 만큼 기록이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'AI READOUT' })).not.toBeInTheDocument()
    expect(urls()).toEqual(['/api/v1/stats/ExamplePlayer'])
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
    expect(await screen.findByText('최근 200경기 분석 · 공식 API')).toBeInTheDocument()
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

  it('H로 헤비, 버튼으로 다시 라이트 — 재조회 없이 같은 stats로 바꾼다 (FR-06)', async () => {
    const { urls } = routeFetch({ stats: [() => json(STATS)], comment: [pending] })
    const { user } = setup()
    await screen.findByRole('region', { name: 'PROFILE' })

    await user.keyboard('h')
    expect(screen.getByText('헤비 뷰는 다음 단계에서 연결됩니다.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '라이트 뷰로 보기' }))
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
