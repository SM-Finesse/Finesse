import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LangProvider } from '../i18n/LangProvider'
import { RetryButton } from './RetryButton'

afterEach(() => {
  vi.useRealTimers()
})

function renderButton(wait: number) {
  const onRetry = vi.fn()
  render(
    <LangProvider initial="ko">
      <RetryButton wait={wait} onRetry={onRetry} />
    </LangProvider>,
  )
  return onRetry
}

describe('RetryButton', () => {
  it('기다릴 시간 동안 남은 초를 세며 잠겨 있다가 풀린다', () => {
    vi.useFakeTimers()
    const onRetry = renderButton(5)
    expect(screen.getByRole('button')).toHaveTextContent('5초 후 다시 시도')
    expect(screen.getByRole('button')).toBeDisabled()

    act(() => vi.advanceTimersByTime(4000))
    expect(screen.getByRole('button')).toHaveTextContent('1초 후 다시 시도')

    act(() => vi.advanceTimersByTime(1000))
    expect(screen.getByRole('button')).toHaveTextContent(/^다시 시도$/)
    fireEvent.click(screen.getByRole('button'))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('기다릴 필요가 없으면 바로 누를 수 있다', () => {
    const onRetry = renderButton(0)
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })
})
