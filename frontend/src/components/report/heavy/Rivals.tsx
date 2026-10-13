import { useState } from 'react'
import type { RivalItem } from '../../../api/types'
import { useI18n } from '../../../i18n/context'
import { cx } from '../../../lib/cx'
import { pageList, rivalSummary, rivalTag, winRateOf, type RivalTag } from '../../../lib/rivals'
import { StatBox, Strip } from './Chapter'

const PER = 20
const COLS = 'grid grid-cols-[32px_5px_minmax(0,1fr)_62px_58px_50px_50px_148px_58px] items-center gap-[11px] px-2.5'
const TAG: Record<Exclude<RivalTag, null>, string> = {
  bad: 'text-[#FF9A93] border-[#8A3A36] bg-[rgba(224,72,63,.16)]',
  good: 'text-[#9FF3DF] border-[#2E7F6E] bg-[rgba(160,242,223,.14)]',
  even: 'text-[#9FD3F0] border-[#2C5D80] bg-[rgba(58,169,238,.14)]',
}

export function Tag({ kind, children }: { kind: Exclude<RivalTag, null>; children: string }) {
  return <span className={cx('inline-grid h-5 place-items-center rounded border-2 px-[9px] font-display text-[10px] font-extrabold tracking-[.08em]', TAG[kind])}>{children}</span>
}

/** 자주 만난 상대 — 천적·우세·반복 조우 타일 / 태그 범례 / 순위표 / 페이지 */
export function RivalBoard({ items }: { items: RivalItem[] }) {
  const { t } = useI18n()
  const r = t.report.heavy.rivals
  const [page, setPage] = useState(1)
  const total = Math.max(1, Math.ceil(items.length / PER))
  const cur = Math.min(page, total)
  const rows = items.slice((cur - 1) * PER, cur * PER)
  const { repeat, worst, best } = rivalSummary(items)
  const tagText = { bad: r.nemesis, good: r.edge, even: r.even }
  const recordOf = (x: RivalItem | null) => (x ? r.record(x.wins, x.losses, Math.round(winRateOf(x))) : undefined)

  return (
    <>
      <Strip>
        <StatBox k={r.nemesis} v={worst?.nickname_masked ?? '—'} s={recordOf(worst)} color="var(--color-loss)" />
        <StatBox k={r.edge} v={best?.nickname_masked ?? '—'} s={recordOf(best)} color="var(--color-win)" />
        <StatBox k={r.repeat} v={r.people(repeat.length)} s={r.repeatSub} color="#fff" />
      </Strip>

      <div className="mt-1 mb-3 flex flex-wrap items-center gap-2.5">
        <Tag kind="bad">{r.nemesis}</Tag>
        <span className="text-[13px] text-[#7A8A99]">{r.nemesisRule}</span>
        <span className="ml-2">
          <Tag kind="good">{r.edge}</Tag>
        </span>
        <span className="text-[13px] text-[#7A8A99]">{r.edgeRule}</span>
        <span className="ml-2">
          <Tag kind="even">{r.even}</Tag>
        </span>
        <span className="text-[13px] text-[#7A8A99]">{r.evenRule}</span>
        <span className="ml-auto inline-flex items-center gap-[7px] rounded border-2 border-[#2C5573] bg-[#0E1E2B] px-2.5 py-1 text-xs text-muted">
          {r.noLink} <b className="font-semibold text-ink">{r.noLinkB}</b>
        </span>
      </div>

      <div className="overflow-x-auto">
        <div role="table" aria-label={t.report.heavy.chapters.rivals} className="min-w-[680px]">
          <div role="row" className={cx(COLS, 'h-[26px] pb-0.5 font-mono text-[10px] tracking-[.14em] text-faint')}>
            <span role="columnheader">#</span>
            <span aria-hidden="true" />
            <span role="columnheader">USERNAME</span>
            <span role="columnheader">PATTERN</span>
            <span role="columnheader" className="text-right">GAMES</span>
            <span role="columnheader" className="text-right">W</span>
            <span role="columnheader" className="text-right">L</span>
            <span role="columnheader">RECORD</span>
            <span role="columnheader" className="text-right">WIN %</span>
          </div>
          <div className="flex flex-col gap-px">
            {rows.map((x, i) => {
              const n = (cur - 1) * PER + i + 1
              const wr = winRateOf(x)
              const tag = rivalTag(x)
              const bar = wr >= 60 ? '#A0F2DF' : wr >= 45 ? '#9FB8C6' : '#FF7A72'
              return (
                <div
                  key={`${x.nickname_masked}-${n}`}
                  role="row"
                  className={cx(
                    COLS,
                    'h-[34px] rounded border font-mono text-[13px] transition-colors even:bg-white/[.045] hover:border-primary-bright/35 hover:bg-primary-bright/10',
                    n <= 3 ? 'border-frame bg-white/[.022]' : 'border-white/[.06] bg-white/[.022]',
                  )}
                >
                  <span role="cell" className="text-right font-num text-[11px] font-bold tracking-[.04em] text-faint">{n}</span>
                  <span aria-hidden="true" className="h-[22px] rounded-[1px]" style={{ background: bar }} />
                  <span role="cell" className={cx('truncate tracking-[.09em]', n <= 3 ? 'text-head' : 'text-ink')}>{x.nickname_masked}</span>
                  <span role="cell">{tag && <Tag kind={tag}>{tagText[tag]}</Tag>}</span>
                  <span role="cell" className="text-right font-num font-bold text-white tabular-nums [text-shadow:0_1px_0_var(--color-ink-out)]">{x.matches}</span>
                  <span role="cell" className="text-right font-num font-bold text-win tabular-nums [text-shadow:0_1px_0_var(--color-ink-out)]">{x.wins}</span>
                  <span role="cell" className="text-right font-num font-bold text-loss tabular-nums [text-shadow:0_1px_0_var(--color-ink-out)]">{x.losses}</span>
                  <span role="cell" aria-hidden="true" className="flex gap-0.5">
                    {Array.from({ length: x.matches }, (_, k) => (
                      <i
                        key={k}
                        className={cx('h-[13px] flex-1 rounded-[1px]', k < x.wins ? 'bg-win' : 'bg-loss')}
                        style={{ boxShadow: 'inset 0 3px 0 rgba(255,255,255,.24), inset 0 -3px 0 rgba(0,0,0,.28)' }}
                      />
                    ))}
                  </span>
                  <span role="cell" className={cx('text-right font-num font-bold tabular-nums [text-shadow:0_1px_0_var(--color-ink-out)]', wr >= 50 ? 'text-win' : 'text-loss')}>
                    {wr.toFixed(0)}
                    <span className="relative -top-[.02em] text-[.56em] text-muted">%</span>
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      </div>

      <nav aria-label="pages" className="flex items-center justify-center gap-1.5 pt-[18px] pb-1">
        <PageBtn label="‹" aria={r.prev} disabled={cur === 1} onClick={() => setPage(cur - 1)} />
        {pageList(cur, total).map((p, i) =>
          p === '…' ? (
            <span key={`d${i}`} className="px-0.5 font-mono text-faint">…</span>
          ) : (
            <PageBtn key={p} label={String(p)} current={p === cur} onClick={() => setPage(p)} />
          ),
        )}
        <PageBtn label="›" aria={r.next} disabled={cur === total} onClick={() => setPage(cur + 1)} />
      </nav>
      <p className="m-0 text-center text-[13px] text-[#7A8A99]">{r.showing(items.length, (cur - 1) * PER + 1, Math.min(cur * PER, items.length))}</p>
    </>
  )
}

function PageBtn({ label, aria, current, disabled, onClick }: { label: string; aria?: string; current?: boolean; disabled?: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={aria}
      aria-current={current ? 'page' : undefined}
      disabled={disabled}
      onClick={onClick}
      className={cx(
        'h-9 min-w-9 rounded border bg-surface-2 px-2.5 font-num text-[13px] font-bold transition-all disabled:cursor-not-allowed disabled:opacity-40',
        current ? 'border-faint text-ink' : 'border-line text-muted hover:not-disabled:border-faint hover:not-disabled:text-ink',
      )}
    >
      {label}
    </button>
  )
}

/** 상대 데이터 취급 공지 — 분석이 아니라 읽을거리라 코멘트 아래 꼬리로 둔다 */
export function RivalPolicy() {
  const { t } = useI18n()
  const r = t.report.heavy.rivals
  return (
    <div className="flex items-start gap-3.5 rounded-[10px] border-3 border-signal/40 bg-signal/[.07] px-5 py-[18px] shadow-[inset_0_0_0_2px_rgba(255,255,255,.10),0_5px_0_rgba(0,0,0,.40)]">
      <span aria-hidden="true" className="grid size-6 flex-none place-items-center rounded-[2px] border border-signal/42 bg-signal/16 text-sm font-bold text-signal">
        i
      </span>
      <div className="min-w-0">
        <h4 className="m-0 text-[15px] font-semibold text-[#DCCB74]">{r.policyTitle}</h4>
        {r.policy.map(([b, text], i) => (
          <p key={b} className={cx('mb-0 text-[13px] text-muted', i === 0 ? 'mt-[7px]' : 'mt-[5px]')}>
            <b className="text-ink">{b}</b> — {text}
            {i === 0 && (
              <>
                {' '}
                {r.policyExamples.map((ex, k) => (
                  <span key={ex}>
                    {k > 0 && ' · '}
                    <span className="font-mono text-sm tracking-[.06em]">{ex}</span>
                  </span>
                ))}
              </>
            )}
          </p>
        ))}
      </div>
    </div>
  )
}
