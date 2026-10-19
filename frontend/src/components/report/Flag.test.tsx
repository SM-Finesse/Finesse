import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Flag } from './Flag'

describe('Flag', () => {
  it('국가 코드에 맞는 국기 그림을 불러온다 — 소문자로 와도', async () => {
    render(<Flag code="kr" label="대한민국" />)
    await waitFor(() => expect(screen.getByRole('img', { name: '대한민국' }).getAttribute('src')).toMatch(/svg/))
  })

  it('국기 그림이 없는 특수 코드(XM)는 코드 글자로 보여준다', () => {
    render(<Flag code="XM" label="XM" />)
    expect(screen.getByLabelText('XM')).toHaveTextContent('XM')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
})
