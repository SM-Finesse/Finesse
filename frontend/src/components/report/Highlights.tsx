import { useState } from 'react'
import type { StatsResponse } from '../../api/types'
import type { CommentState } from '../../hooks/useLightComment'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { mapHighlights, TREND_MARK, type HighlightItem } from '../../lib/stats'
import { BigNum, Notice } from './parts'

const CARD_COLORS = ['var(--color-piece-s)', 'var(--color-piece-l)', 'var(--color-piece-t)']
const VALUE_COLOR = { up: 'text-delta-up', down: 'text-delta-down', even: 'text-head' } as const

function Card({ item, index, on, dim, onHover, onToggle }: {
  item: HighlightItem
  index: number
  on: boolean
  dim: boolean
  onHover: (stat: string | null) => void
  onToggle: () => void
}) {
  const { t, lang } = useI18n()
  const { ev, sentence } = item
  const color = CARD_COLORS[index % CARD_COLORS.length]
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onToggle}
      onMouseEnter={() => onHover(ev.stat)}
      onMouseLeave={() => onHover(null)}
      onFocus={() => onHover(ev.stat)}
      onBlur={() => onHover(null)}
      style={{ borderColor: color }}
      className={cx(
        'panel block w-full overflow-hidden p-5 text-left transition-[transform,opacity,background-color] duration-200 ease-(--ease-arcade)',
        'hover:-translate-y-px',
        on ? 'bg-[#173042]' : 'bg-surface',
        dim && 'opacity-50',
      )}
    >
      <span className="flex items-center gap-[9px]">
        <span className="font-mono text-xs" style={{ color }}>{String(index + 1).padStart(2, '0')}</span>
        <span className="font-display text-[15px] font-semibold text-ink">{ev.meta.label[lang]}</span>
      </span>
      <span className={cx('num-hud mt-2.5 block text-[34px] leading-[1.1]', VALUE_COLOR[ev.trend ?? 'even'])}>
        <BigNum value={ev.text} />
        {ev.trend && <span className="ml-1.5 text-[.5em]">{TREND_MARK[ev.trend]}</span>}
      </span>
      <span className="mt-2.5 block min-h-12 text-[15px] leading-[1.72] text-[#B7C6D2]">{sentence}</span>
      <span className="relative mt-3 flex items-baseline justify-between gap-2.5 pt-3 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-[linear-gradient(90deg,transparent,rgba(255,255,255,.14)_12%,rgba(255,255,255,.14)_88%,transparent)]">
        <span className="text-[13px] text-faint">{t.report.hl.evidence}</span>
        <span className="font-mono text-sm text-ink">
          {ev.meta.code} {ev.text}
        </span>
      </span>
    </button>
  )
}

export function Highlights({ data, comment }: { data: StatsResponse; comment: CommentState }) {
  const { t } = useI18n()
  const hl = t.report.hl
  const [pinned, setPinned] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const active = hovered ?? pinned

  if (data.cold_start) return <Notice title={hl.coldTitle}>{hl.coldBody(data.match_count)}</Notice>
  if (comment.status === 'error') return <Notice title={hl.failed} />
  if (comment.status !== 'success') {
    return (
      <div className="grid gap-5 md:grid-cols-3" aria-busy="true">
        {[0, 1, 2].map((i) => (
          <div key={i} className="panel bg-surface p-5">
            <div className="skeleton h-3.5 w-1/3" />
            <div className="skeleton mt-4 h-8 w-1/2" />
            <div className="skeleton mt-4 h-3.5 w-full" />
            <div className="skeleton mt-2 h-3.5 w-4/5" />
          </div>
        ))}
      </div>
    )
  }

  const items = mapHighlights(comment.data.highlights, data.delta_metrics)
  if (!items.length) return <Notice title={hl.noneTitle}>{hl.noneBody}</Notice>

  return (
    <div className="grid gap-5 md:grid-cols-3">
      {items.map((item, i) => (
        <Card
          key={item.ev.stat}
          item={item}
          index={i}
          on={active === item.ev.stat}
          dim={active !== null && active !== item.ev.stat}
          onHover={setHovered}
          onToggle={() => setPinned((p) => (p === item.ev.stat ? null : item.ev.stat))}
        />
      ))}
    </div>
  )
}
