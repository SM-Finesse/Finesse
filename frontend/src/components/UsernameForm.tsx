import type { FormEvent, Ref } from 'react'
import { useI18n } from '../i18n/context'
import { cx } from '../lib/cx'
import type { UsernameError } from '../lib/username'
import type { View } from '../types'
import { ViewSwitch } from './Switches'

export const USERNAME_ERROR_ID = 'username-error'

interface Props {
  value: string
  onChange: (value: string) => void
  onSubmit: () => void
  error: UsernameError | null
  view: View
  onToggleView: () => void
  inputRef?: Ref<HTMLInputElement>
}

/** 유저명 입력 + 분석 범위 스위치 + 분석 버튼. 검증은 부모가 하고 여기서는 보여주기만 한다 */
export function UsernameForm({ value, onChange, onSubmit, error, view, onToggleView, inputRef }: Props) {
  const { t } = useI18n()
  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    onSubmit()
  }

  return (
    <form role="search" noValidate onSubmit={handleSubmit}>
      <div className="flex gap-2 max-sm:flex-col">
        <div
          className={cx(
            'cut-corner flex h-14 items-center sm:flex-1 gap-2.5 border bg-surface px-4 transition-colors',
            'shadow-[inset_0_1px_0_rgba(255,255,255,.05),inset_0_0_0_1px_rgba(0,0,0,.25)]',
            error
              ? 'border-loss focus-within:border-loss'
              : 'border-line focus-within:border-primary-bright',
          )}
        >
          <span aria-hidden="true" className="flex-none font-mono text-[11px] font-medium tracking-[.08em] text-faint max-sm:hidden">{t.usernameLabel}</span>
          <input
            ref={inputRef}
            name="username"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder={t.placeholder}
            aria-label={t.usernameLabel}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? USERNAME_ERROR_ID : undefined}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            className="min-w-0 flex-1 border-0 bg-transparent text-base text-ink outline-none placeholder:text-faint"
          />
          <span className="h-[26px] w-px flex-none bg-line" />
          <ViewSwitch view={view} onToggle={onToggleView} />
        </div>
        <button
          type="submit"
          className="inline-flex h-14 items-center justify-center rounded-md border-2 border-white bg-primary-bright px-6 font-display text-lg font-semibold text-white transition-colors hover:bg-frame active:translate-y-px"
        >
          {t.analyze}
        </button>
      </div>
      {error && (
        <p id={USERNAME_ERROR_ID} role="alert" className="mt-2.5 flex items-center gap-2 text-sm text-loss">
          <span aria-hidden="true" className="grid size-[18px] place-items-center rounded-[3px] border-2 border-loss/60 bg-loss/15 font-display text-[11px] font-extrabold">
            !
          </span>
          {t.errors[error]}
        </p>
      )}
    </form>
  )
}
