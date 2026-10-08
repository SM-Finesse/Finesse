import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { LangProvider } from '../i18n/LangProvider'
import type { AnalyzeRequest, Lang, View } from '../types'
import { LandingPage } from './LandingPage'

function setup({ lang = 'ko' as Lang, initialView = 'light' as View, initialUsername = '', recent = [] as string[] } = {}) {
  const onAnalyze = vi.fn<(req: AnalyzeRequest) => void>()
  const onRemoveRecent = vi.fn<(name: string) => void>()
  function Harness() {
    const [view, setView] = useState<View>(initialView)
    return (
      <LandingPage
        view={view}
        onViewChange={setView}
        onAnalyze={onAnalyze}
        initialUsername={initialUsername}
        recent={recent}
        onRemoveRecent={onRemoveRecent}
      />
    )
  }
  const user = userEvent.setup()
  render(
    <LangProvider initial={lang}>
      <Harness />
    </LangProvider>,
  )
  return { user, onAnalyze, onRemoveRecent, input: screen.getByRole('textbox', { name: '유저명' }) }
}

describe('LandingPage — 유저명 받아오기', () => {
  it('입력한 유저명을 앞뒤 공백을 떼고 현재 뷰와 함께 넘긴다', async () => {
    const { user, onAnalyze, input } = setup()
    await user.type(input, '  ExamplePlayer ')
    await user.click(screen.getByRole('button', { name: '분석하기' }))
    expect(onAnalyze).toHaveBeenCalledExactlyOnceWith({ username: 'ExamplePlayer', view: 'light' })
  })

  it('검색창에서 Enter를 누르면 제출된다', async () => {
    const { user, onAnalyze, input } = setup()
    await user.type(input, 'player_12{Enter}')
    expect(onAnalyze).toHaveBeenCalledWith({ username: 'player_12', view: 'light' })
  })

  it('빈 값이면 넘기지 않고 오류를 알린 뒤 입력칸으로 포커스를 돌린다', async () => {
    const { user, onAnalyze, input } = setup()
    await user.click(screen.getByRole('button', { name: '분석하기' }))
    expect(onAnalyze).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('유저명을 입력하세요.')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('유저명을 입력하세요.')
    expect(input).toHaveFocus()
  })

  it('치는 도중에는 오류를 띄우지 않고, 제출 실패 뒤에는 고치는 즉시 오류가 사라진다', async () => {
    const { user, input } = setup()
    await user.type(input, 'a.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.keyboard('{Enter}')
    expect(screen.getByRole('alert')).toHaveTextContent('영문 · 숫자 · _ · -')

    await user.clear(input)
    await user.type(input, 'ab')
    expect(screen.getByRole('alert')).toHaveTextContent('3자 이상')

    await user.type(input, 'c')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(input).not.toHaveAttribute('aria-invalid')
  })

  it('16자를 넘으면 넘기지 않는다', async () => {
    const { user, onAnalyze, input } = setup()
    await user.type(input, 'a'.repeat(17) + '{Enter}')
    expect(onAnalyze).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('16자 이하')
  })

  it('최근 검색이 없으면 안내만 보인다', () => {
    setup()
    expect(screen.getByText('검색한 유저가 여기에 쌓입니다')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: '최근 검색' })).not.toBeInTheDocument()
  })

  it('최근 검색을 누르면 그 유저로 바로 조회하고, ×는 지우기만 한다', async () => {
    const { user, onAnalyze, onRemoveRecent } = setup({ recent: ['icly', 'turtle'] })
    const list = screen.getByRole('list', { name: '최근 검색' })
    expect(list).toHaveTextContent(/icly.*turtle/)

    await user.click(screen.getByRole('button', { name: 'turtle 최근 검색에서 지우기' }))
    expect(onRemoveRecent).toHaveBeenCalledWith('turtle')
    expect(onAnalyze).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'icly' }))
    expect(onAnalyze).toHaveBeenCalledExactlyOnceWith({ username: 'icly', view: 'light' })
  })

  it('스위치와 모드 카드가 같은 뷰 상태를 가리키고, 제출값에 반영된다', async () => {
    const { user, onAnalyze, input } = setup()
    const sw = screen.getByRole('switch')
    expect(sw).toHaveAttribute('aria-checked', 'false')

    await user.click(sw)
    expect(sw).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('button', { name: /HEAVY/ })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: /LIGHT/ }))
    expect(sw).toHaveAttribute('aria-checked', 'false')

    await user.click(screen.getByRole('button', { name: /HEAVY/ }))
    await user.type(input, 'oak{Enter}')
    expect(onAnalyze).toHaveBeenCalledWith({ username: 'oak', view: 'heavy' })
  })

  it('L / H 단축키는 입력칸 밖에서만 뷰를 바꾼다 — 유저명에 h가 들어가도 안전', async () => {
    const { user, input } = setup()
    const sw = screen.getByRole('switch')

    await user.type(input, 'hh')
    expect(sw).toHaveAttribute('aria-checked', 'false')

    await user.keyboard('{Escape}')
    expect(input).not.toHaveFocus()
    await user.keyboard('h')
    expect(sw).toHaveAttribute('aria-checked', 'true')
    await user.keyboard('L')
    expect(sw).toHaveAttribute('aria-checked', 'false')
  })

  it('입력칸 밖에서 Enter를 눌러도 제출된다 (버튼에 포커스가 있을 때는 그 버튼 동작만)', async () => {
    const { user, onAnalyze } = setup({ initialUsername: 'ExamplePlayer' })
    await user.keyboard('{Enter}')
    expect(onAnalyze).toHaveBeenCalledTimes(1)

    screen.getByRole('button', { name: /HEAVY/ }).focus()
    await user.keyboard('{Enter}')
    expect(onAnalyze).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true')
  })

  it('느낌표 배지로 설명 패널을 열고, ESC로 닫으면 배지로 포커스가 돌아온다', async () => {
    const { user } = setup()
    const badge = screen.getByRole('button', { name: '이 사이트 설명 열기' })
    expect(badge).toHaveAttribute('aria-expanded', 'false')

    await user.click(badge)
    const panel = screen.getByRole('region', { name: '이 사이트 설명' })
    expect(badge).toHaveAttribute('aria-expanded', 'true')
    expect(panel).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('region', { name: '이 사이트 설명' })).not.toBeInTheDocument()
    expect(badge).toHaveFocus()
  })

  it('EN으로 바꾸면 문구와 오류가 영어로 나온다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: 'EN' }))
    const input = screen.getByRole('textbox', { name: 'USERNAME' })
    expect(input).toHaveAttribute('placeholder', 'Enter a username')

    await user.click(screen.getByRole('button', { name: 'Analyze' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a username.')
    expect(localStorage.getItem('finesse.lang')).toBe('en')
  })
})
