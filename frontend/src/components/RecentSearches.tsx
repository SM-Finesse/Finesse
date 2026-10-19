import { useI18n } from '../i18n/context'

interface Props {
  names: string[]
  onPick: (name: string) => void
  onRemove: (name: string) => void
}

/** 최근 검색 — 누르면 바로 다시 조회, ×는 목록에서만 지운다 */
export function RecentSearches({ names, onPick, onRemove }: Props) {
  const { t } = useI18n()
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-[13px] text-[#7A8A99]">{t.recentTitle}</span>
      {names.length === 0 ? (
        <span className="text-[13px] text-faint">{t.recentEmpty}</span>
      ) : (
        <ul className="m-0 flex list-none flex-wrap gap-2 p-0" aria-label={t.recentTitle}>
          {names.map((name) => (
            <li
              key={name}
              className="flex items-stretch overflow-hidden rounded border-2 border-[#35617F] bg-surface shadow-[inset_0_1px_0_rgba(255,255,255,.06)] transition-colors hover:border-primary-bright"
            >
              <button
                type="button"
                onClick={() => onPick(name)}
                className="py-[5px] pr-1.5 pl-3 font-display text-xs text-muted transition-colors hover:text-primary-bright"
              >
                {name}
              </button>
              <button
                type="button"
                onClick={() => onRemove(name)}
                aria-label={t.recentRemove(name)}
                className="grid w-6 place-items-center border-l border-[#35617F] text-sm leading-none text-faint transition-colors hover:bg-[#1E3A52] hover:text-head"
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
