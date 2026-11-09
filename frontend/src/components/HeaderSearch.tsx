import { useState, type FormEvent } from 'react'
import { useI18n } from '../i18n/context'
import { cx } from '../lib/cx'
import { validateUsername } from '../lib/username'

const ERROR_ID = 'header-search-error'

/** 결과 화면 헤더의 작은 검색창 — 랜딩으로 돌아가지 않고 다른 유저를 바로 조회한다 */
export function HeaderSearch({ onSearch }: { onSearch: (username: string) => void }) {
  const { t } = useI18n()
  const [query, setQuery] = useState('')
  /* 랜딩 폼과 같이, 제출을 한 번 시도한 뒤부터 입력마다 다시 검사한다 */
  const [attempted, setAttempted] = useState(false)
  const check = validateUsername(query)
  const error = attempted && !check.ok ? check.error : null

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setAttempted(true)
    if (!check.ok) return
    onSearch(check.value)
    setQuery('')
    setAttempted(false)
    ;(document.activeElement as HTMLElement | null)?.blur()
  }

  return (
    <form role="search" noValidate onSubmit={submit} className="relative min-w-0 flex-1 sm:max-w-[340px]">
      <div
        className={cx(
          'flex h-[38px] items-center gap-2 rounded border-2 bg-surface px-3 transition-colors',
          error ? 'border-loss' : 'border-line focus-within:border-primary-bright',
        )}
      >
        <svg aria-hidden="true" viewBox="0 0 16 16" className="size-3.5 flex-none text-faint">
          <circle cx="7" cy="7" r="5" fill="none" stroke="currentColor" strokeWidth="2" />
          <path d="M11 11l3.5 3.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
        <input
          name="username"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') e.currentTarget.blur()
          }}
          placeholder={t.placeholder}
          aria-label={t.usernameLabel}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? ERROR_ID : undefined}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          className="min-w-0 flex-1 border-0 bg-transparent text-sm text-ink outline-none placeholder:text-faint"
        />
      </div>
      {error && (
        <p id={ERROR_ID} role="alert" className="absolute top-full left-0 mt-1.5 rounded border border-loss/60 bg-deep px-2.5 py-1 text-xs whitespace-nowrap text-loss">
          {t.errors[error]}
        </p>
      )}
    </form>
  )
}
