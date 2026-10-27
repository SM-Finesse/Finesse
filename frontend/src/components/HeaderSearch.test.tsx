import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LangProvider } from '../i18n/LangProvider'
import { HeaderSearch } from './HeaderSearch'

function renderSearch() {
  const onSearch = vi.fn()
  render(
    <LangProvider initial="ko">
      <HeaderSearch onSearch={onSearch} />
    </LangProvider>,
  )
  return { onSearch, input: screen.getByRole('textbox', { name: '유저명' }) }
}

describe('HeaderSearch', () => {
  it('유저명을 넣고 Enter를 누르면 앞뒤 공백을 뺀 이름으로 조회하고 입력을 비운다', () => {
    const { onSearch, input } = renderSearch()
    fireEvent.change(input, { target: { value: '  player_a ' } })
    fireEvent.submit(input.closest('form')!)
    expect(onSearch).toHaveBeenCalledWith('player_a')
    expect(input).toHaveValue('')
  })

  it('잘못된 유저명이면 조회하지 않고 오류를 보여준다', () => {
    const { onSearch, input } = renderSearch()
    fireEvent.change(input, { target: { value: 'ab' } })
    fireEvent.submit(input.closest('form')!)
    expect(onSearch).not.toHaveBeenCalled()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
