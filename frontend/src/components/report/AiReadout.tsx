import { useCallback, useId, useRef, useState, type ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useDismiss } from '../../hooks/useDismiss'
import type { CommentState } from '../../hooks/useLightComment'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { retryWait } from '../../lib/retry'
import { HIGHLIGHT_COLORS, linkSummary, mapHighlights, num, readable, skippedOf, TREND_MARK, type HighlightItem, type StatKey } from '../../lib/stats'
import { RetryButton } from '../RetryButton'
import type { HighlightFocus } from './Highlights'
import { BigNum, Caption, PanelTag } from './parts'

/** AI 총평 — stats가 먼저 그려진 뒤 도착한다(FR-04). 오는 동안은 같은 크기의 자리를 잡아 둔다 */
export function AiReadout({ comment, onRetry, data, focus }: { comment: CommentState; onRetry: () => void; data?: StatsResponse; focus?: HighlightFocus }) {
  const { t, lang } = useI18n()
  const a = t.report.ai
  if (comment.status === 'idle') return null

  /* 코멘트 바로 아래 GAMES 줄 — 분석한 경기 수 · 수집했지만 뺀 경기 수(헤더·프로필에서 옮겨 옴) */
  const skipped = data ? skippedOf(data) : null
  const games = data && (
    <p className="m-0 flex basis-full flex-wrap items-baseline gap-x-2 border-t border-line-soft pt-1.5 leading-tight" title={skipped === null ? undefined : a.skippedWhy}>
      <span className="font-display text-[11px] font-bold tracking-[.18em] text-faint">GAMES</span>
      <span className="font-num text-[18px] font-bold text-head tabular-nums">{num(data.match_count)}</span>
      <span className="text-[13px] text-muted">
        {t.report.profile.gamesSub}
        {skipped !== null && ` · ${a.skipped(data.match_count + skipped, skipped)}`}
      </span>
    </p>
  )

  let body
  if (comment.status === 'loading') {
    body = (
      <>
        <PanelTag>AI READOUT</PanelTag>
        <div role="status" aria-label={a.loading} className="min-w-[120px] flex-1">
          <div className="skeleton h-[18px] w-3/4" />
        </div>
        <Caption className="flex-none">{a.loading}</Caption>
        {games}
      </>
    )
  } else if (comment.status === 'error') {
    const { code } = comment.error
    const msg = code === 'SERVER_BUSY' ? t.statsErrors.busy : code === 'LLM_UNAVAILABLE' ? a.unavailable : a.failed
    body = (
      <>
        <PanelTag>AI READOUT</PanelTag>
        <p role="alert" className="m-0 flex-1 text-[15px] text-muted">{msg}</p>
        <RetryButton
          wait={retryWait(comment.error)}
          onRetry={onRetry}
          className="h-[34px] rounded border-2 border-[#5E90B0] bg-surface-2 px-3 text-sm text-ink transition-colors hover:bg-line hover:text-white disabled:cursor-wait disabled:border-line disabled:text-faint disabled:hover:bg-surface-2"
        />
        {games}
      </>
    )
  } else {
    const summary = readable(comment.data.light_summary)
    const items = focus ? mapHighlights(comment.data.highlights, data?.delta_metrics) : []
    /* 문장에서 하이라이트 지표를 가리키는 구절을 하나도 못 찾으면 클릭할 곳 없이 평문으로 */
    const linkable = linkSummary(summary, items.map((i) => i.ev.stat), lang, sentencesOf(items)).some((p) => p.stat)
    body = (
      <>
        <PanelTag accent>AI READOUT</PanelTag>
        {linkable && focus ? (
          <LinkedSummary text={summary} items={items} focus={focus} below={<><Caption className="flex-none max-md:hidden">{a.hint}</Caption>{games}</>} />
        ) : (
          <>
            <p className="m-0 min-w-0 flex-1 text-base leading-[1.6] font-medium text-head">{summary}</p>
            {games}
          </>
        )}
      </>
    )
  }

  return (
    <section className="panel grid grid-cols-[4px_minmax(0,1fr)] overflow-hidden bg-surface" aria-label="AI READOUT">
      <div className="bg-primary-bright/70" />
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-1.5 px-[22px] pt-3 pb-2">{body}</div>
    </section>
  )
}

const VALUE_COLOR = { up: 'text-delta-up', down: 'text-delta-down', even: 'text-head' } as const

/** 카드 문장 — 총평에서 지표 이름이 안 나와도 카드 문장과 겹치는 구절로 잇는다 */
const sentencesOf = (items: HighlightItem[]) => Object.fromEntries(items.map((i) => [i.ev.stat, readable(i.sentence)]))

/**
 * 총평 속 구절 → 하이라이트 지표. 지표를 가리키는 구절에 밑줄을 긋고, 누르면 총평 바로 아래에 그 지표 창이 뜬다
 * (지표 이름 · 근거 수치 · AI 문장). 아래 같은 카드도 함께 켜진다. 다시 누르거나 Esc · 바깥 클릭이면 닫힌다.
 * 문장에서 가리키는 말을 못 찾은 하이라이트는 클릭할 곳을 따로 만들지 않는다 — 아래 카드로 본다.
 */
function LinkedSummary({ text, items, focus, below }: { text: string; items: HighlightItem[]; focus: HighlightFocus; below?: ReactNode }) {
  const { t, lang } = useI18n()
  const [open, setOpen] = useState<StatKey | null>(null)
  const ref = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const { pin } = focus
  const close = useCallback(() => {
    setOpen(null)
    pin(null)
  }, [pin])
  useDismiss(open !== null, close, ref)

  const stats = items.map((i) => i.ev.stat)
  const parts = linkSummary(text, stats, lang, sentencesOf(items))
  const indexOf = (stat: StatKey) => stats.indexOf(stat)
  const colorOf = (stat: StatKey) => HIGHLIGHT_COLORS[indexOf(stat) % HIGHLIGHT_COLORS.length]
  const shown = open ? items[indexOf(open)] : null

  const link = (stat: StatKey, label: string, key: string) => {
    const on = open === stat
    return (
      <button
        key={key}
        type="button"
        aria-expanded={on}
        aria-controls={panelId}
        onClick={() => {
          setOpen(on ? null : stat)
          pin(on ? null : stat)
        }}
        style={{ ['--hl' as string]: colorOf(stat) }}
        className={cx(
          'inline cursor-pointer rounded-[3px] px-1 py-px text-left transition-colors [box-decoration-break:clone]',
          'border-b-2 border-(--hl) hover:bg-(--hl)/20',
          on ? 'bg-(--hl)/25 text-white' : 'bg-white/[.04]',
        )}
      >
        {label}
      </button>
    )
  }

  return (
    <div ref={ref} className="contents">
      <p className="m-0 min-w-0 flex-1 text-base leading-[1.75] font-medium text-head">
        {parts.map((p, i) => (p.stat ? link(p.stat, p.text, `p${i}`) : <span key={`p${i}`}>{p.text}</span>))}
      </p>
      {below}
      {shown && (
        <div
          id={panelId}
          role="region"
          aria-label={t.report.ai.linkTo(shown.ev.meta.label[lang])}
          style={{ borderColor: colorOf(shown.ev.stat) }}
          className="grid basis-full animate-info-in grid-cols-[auto_minmax(0,1fr)] items-center gap-x-5 gap-y-1 rounded-md border-2 bg-[#0E1E2B] px-4 py-3 shadow-[0_10px_24px_rgba(0,0,0,.45)]"
        >
          <div>
            <p className="m-0 flex items-center gap-2">
              <span className="font-mono text-xs" style={{ color: colorOf(shown.ev.stat) }}>
                {String(indexOf(shown.ev.stat) + 1).padStart(2, '0')}
              </span>
              <span className="font-display text-sm font-semibold text-ink">{shown.ev.meta.label[lang]}</span>
            </p>
            <p className={cx('num-hud m-0 mt-1 text-[28px] leading-none whitespace-nowrap', VALUE_COLOR[shown.ev.trend ?? 'even'])}>
              <BigNum value={shown.ev.text} />
              {shown.ev.trend && <span className="ml-1.5 text-[.5em]">{TREND_MARK[shown.ev.trend]}</span>}
            </p>
          </div>
          <div className="min-w-0">
            <p className="m-0 text-[15px] leading-[1.65] text-[#B7C6D2]">{readable(shown.sentence)}</p>
            <p className="m-0 mt-1 font-mono text-xs text-faint">
              {t.report.hl.evidence} · {shown.ev.meta.code} {shown.ev.text}
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
