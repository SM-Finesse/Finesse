import { useI18n } from '../i18n/context'
import { cx } from '../lib/cx'
import type { View } from '../types'

/** 모드 셀렉트 — 게임 메뉴처럼 고르는 패널. 고른 쪽이 결과 화면의 기본 뷰가 된다 */
export function ModeCard({ mode, selected, onSelect }: { mode: View; selected: boolean; onSelect: (v: View) => void }) {
  const { t } = useI18n()
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={() => onSelect(mode)}
      className={cx(
        'panel relative px-6 pt-[22px] pb-6 text-left transition-[transform,background-color,border-color] duration-200 ease-(--ease-arcade)',
        'hover:-translate-y-px hover:border-[#7FD2FF]',
        selected ? 'border-primary-bright bg-[#173042]' : 'bg-surface',
      )}
    >
      <div className="flex items-center gap-3">
        <span className="font-num text-[13px] font-extrabold tracking-[.1em] text-faint">{mode === 'light' ? '01' : '02'}</span>
        <h3 className="m-0 font-display text-2xl font-extrabold tracking-[.02em] text-head">{mode.toUpperCase()}</h3>
        <span className="text-sm text-muted">{t.view[mode]}</span>
      </div>
      <ul className="mt-4 flex list-none flex-col gap-[9px] border-t border-line-soft p-0 pt-3.5">
        {t.modeRows[mode].map(([k, v]) => (
          <li key={k} className="flex gap-2.5 text-sm text-ink">
            <b className="min-w-[52px] flex-none font-num font-bold text-primary-bright">{k}</b>
            <span>{v}</span>
          </li>
        ))}
      </ul>
      <span
        aria-hidden="true"
        className={cx(
          'absolute right-0 bottom-0 px-3.5 py-1.5 font-display text-[10px] font-bold tracking-[.18em]',
          selected ? 'text-primary-bright' : 'text-faint',
        )}
      >
        {selected ? '▸ SELECTED' : 'SELECT'}
      </span>
    </button>
  )
}
