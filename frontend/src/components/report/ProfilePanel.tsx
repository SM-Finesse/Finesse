import type { ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { num, rankLabel, signed, trendOf } from '../../lib/stats'
import { BigNum, Caption, DeltaInline } from './parts'

function Cell({ k, children, sub, accent, tag }: { k: string; children: ReactNode; sub?: ReactNode; accent?: boolean; tag?: string }) {
  return (
    <div className={cx('relative min-w-0 px-4 py-[15px]', accent ? 'bg-[#1A3348]' : 'bg-cell')}>
      {tag && (
        <span className="absolute top-[13px] right-3.5 rounded-[3px] border border-primary-bright/40 px-1.5 py-px font-display text-[9px] font-bold tracking-[.12em] text-primary-bright">
          {tag}
        </span>
      )}
      <div className={cx('font-display text-[9.5px] font-bold tracking-[.2em]', accent ? 'text-primary-bright' : 'text-faint')}>{k}</div>
      <div className="num-hud mt-[5px] text-[23px] tracking-[-.018em] text-white">{children}</div>
      {sub && <div className="mt-1.5 flex flex-col items-start gap-1.5 text-[11.5px] text-muted">{sub}</div>}
    </div>
  )
}

/** 유저 기본 정보 — /stats의 profile · fixed_metrics 값을 그대로 보여준다 */
export function ProfilePanel({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const p = t.report.profile
  const rank = rankLabel(data.profile.rank)
  const wins = Math.round(data.fixed_metrics.win_rate * data.match_count)
  const trDelta = data.delta_metrics?.tr_trend_delta

  return (
    <section className="panel overflow-hidden bg-surface" aria-label="PROFILE">
      <div className="grid grid-cols-2 gap-0.5 bg-deep md:grid-cols-[200px_repeat(4,minmax(0,1fr))]">
        <div className="col-span-2 flex min-w-0 flex-col justify-center bg-cell px-4 py-[15px] md:col-span-1">
          <div className="font-display text-[9.5px] font-bold tracking-[.2em] text-faint">RANK</div>
          <div className={cx('mt-[5px] font-display font-extrabold tracking-[.02em]', rank ? 'text-[26px] text-head' : 'text-lg text-muted')}>
            {rank ?? t.report.unranked}
          </div>
          <div className="text-[11.5px] text-muted">{p.season}</div>
        </div>
        <Cell
          k="TR"
          accent
          tag={p.basis}
          sub={
            <>
              {typeof trDelta === 'number' && (
                <span className="flex items-baseline gap-1.5">
                  <DeltaInline text={signed(trDelta, 1)} trend={trendOf(trDelta, 1)} />
                  <Caption className="text-[11px]">{p.trDelta}</Caption>
                </span>
              )}
              <span>{p.trSub}</span>
            </>
          }
        >
          <BigNum value={num(data.profile.tr, 2)} />
        </Cell>
        <Cell k="WIN RATE" sub={p.wl(wins, data.match_count - wins)}>
          <BigNum value={(data.fixed_metrics.win_rate * 100).toFixed(1)} suffix="%" />
        </Cell>
        <Cell k="GLICKO" sub={p.rd(num(data.profile.rd, 1))}>
          <BigNum value={num(data.profile.glicko, 1)} />
        </Cell>
        <Cell k="GAMES" sub={p.gamesSub}>
          {num(data.match_count)}
        </Cell>
      </div>
      {typeof trDelta === 'number' && (
        <div className="border-t-2 border-deep bg-[#0F1D29] px-[18px] py-3">
          <Caption className="text-xs">{p.note}</Caption>
        </div>
      )}
    </section>
  )
}
