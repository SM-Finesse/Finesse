import { useI18n } from '../i18n/context'
import { cx } from '../lib/cx'
import type { Lang, View } from '../types'

export function LangSwitch() {
  const { lang, setLang } = useI18n()
  const langs: Lang[] = ['ko', 'en']
  return (
    <div role="group" aria-label="Language" className="flex gap-0.5 rounded border-2 border-[#2C5573] bg-deep p-0.5">
      {langs.map((l) => (
        <button
          key={l}
          type="button"
          aria-pressed={lang === l}
          onClick={() => setLang(l)}
          className={cx(
            'rounded-[3px] px-2.5 py-[3px] font-display text-[11px] font-extrabold tracking-[.1em] transition-colors',
            lang === l ? 'bg-frame text-[#06131C]' : 'text-faint hover:text-ink',
          )}
        >
          {l.toUpperCase()}
        </button>
      ))}
    </div>
  )
}

/** 라이트 ↔ 헤비 스위치 — 끄면 라이트, 켜면 헤비 */
export function ViewSwitch({ view, onToggle }: { view: View; onToggle: () => void }) {
  const { t } = useI18n()
  const heavy = view === 'heavy'
  const lab = 'font-display text-xs font-medium transition-colors'
  return (
    <button
      type="button"
      role="switch"
      aria-checked={heavy}
      aria-label={t.viewSwitchAria}
      onClick={onToggle}
      className="flex flex-none items-center gap-[9px] px-0.5"
    >
      <span aria-hidden="true" className={cx(lab, heavy ? 'text-faint' : 'text-ink')}>{t.view.light}</span>
      <span
        aria-hidden="true"
        className={cx(
          'relative h-[22px] w-10 rounded-full border transition-colors',
          heavy ? 'border-primary-bright bg-[rgba(102,192,244,.28)]' : 'border-line bg-surface-2',
        )}
      >
        <i
          className={cx(
            'absolute top-[3px] left-[3px] size-3.5 rounded-full transition-transform duration-200 ease-(--ease-arcade)',
            heavy ? 'translate-x-[18px] bg-primary-bright' : 'bg-muted',
          )}
        />
      </span>
      <span aria-hidden="true" className={cx(lab, heavy ? 'text-ink' : 'text-faint')}>{t.view.heavy}</span>
    </button>
  )
}
