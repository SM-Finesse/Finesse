import { describe, expect, it } from 'vitest'
import { validateUsername } from './username'

describe('validateUsername', () => {
  it('영문·숫자·_ 로 된 3~16자를 통과시킨다', () => {
    expect(validateUsername('ExamplePlayer')).toEqual({ ok: true, value: 'ExamplePlayer' })
    expect(validateUsername('player_12')).toEqual({ ok: true, value: 'player_12' })
  })

  it('앞뒤 공백은 잘라내고 대소문자는 보존한다', () => {
    expect(validateUsername('  NewPlayer \t')).toEqual({ ok: true, value: 'NewPlayer' })
  })

  it('길이 경계 — 3자와 16자는 통과, 2자와 17자는 실패', () => {
    expect(validateUsername('oak').ok).toBe(true)
    expect(validateUsername('a'.repeat(16)).ok).toBe(true)
    expect(validateUsername('ab')).toEqual({ ok: false, error: 'tooShort' })
    expect(validateUsername('a'.repeat(17))).toEqual({ ok: false, error: 'tooLong' })
  })

  it('비어 있거나 공백뿐이면 empty', () => {
    expect(validateUsername('')).toEqual({ ok: false, error: 'empty' })
    expect(validateUsername('   ')).toEqual({ ok: false, error: 'empty' })
  })

  it('허용되지 않은 문자는 길이보다 먼저 알린다', () => {
    expect(validateUsername('a-b')).toEqual({ ok: false, error: 'invalidChars' })
    expect(validateUsername('한글닉네임')).toEqual({ ok: false, error: 'invalidChars' })
    expect(validateUsername('two words')).toEqual({ ok: false, error: 'invalidChars' })
    expect(validateUsername('!')).toEqual({ ok: false, error: 'invalidChars' })
  })
})
