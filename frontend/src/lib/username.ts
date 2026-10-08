/* 닉네임 제약: 3~16자, 영문 대소문자·숫자·_·- 만 허용 (TETR.IO 유저명 규칙 — 예: -error404-) */
export const USERNAME_MIN = 3
export const USERNAME_MAX = 16
const USERNAME_CHARS = /^[A-Za-z0-9_-]+$/

export type UsernameError = 'empty' | 'tooShort' | 'tooLong' | 'invalidChars'

export type UsernameResult =
  | { ok: true; value: string }
  | { ok: false; error: UsernameError }

/** 앞뒤 공백은 사용자가 의도한 값이 아니므로 잘라낸 뒤 검사한다. 대소문자는 그대로 둔다. */
export function validateUsername(raw: string): UsernameResult {
  const value = raw.trim()
  if (!value) return { ok: false, error: 'empty' }
  if (!USERNAME_CHARS.test(value)) return { ok: false, error: 'invalidChars' }
  if (value.length < USERNAME_MIN) return { ok: false, error: 'tooShort' }
  if (value.length > USERNAME_MAX) return { ok: false, error: 'tooLong' }
  return { ok: true, value }
}
