import type { ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { num, rankLabel, signed, trendOf } from '../../lib/stats'
import { BigNum, Caption, DeltaInline } from './parts'
import { RankIcon } from './RankIcon'

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

/** 아래 띠의 작은 값 — GLICKO · GAMES */
function Strip({ k, v, unit }: { k: string; v: string; unit?: string }) {
  return (
    <span className="flex items-baseline gap-2">
      <span className="font-display text-[9.5px] font-bold tracking-[.18em] text-faint">{k}</span>
      <span className="font-num text-[17px] font-bold text-head tabular-nums">
        <BigNum value={v} />
      </span>
      {unit && <span className="text-[11px] text-muted">{unit}</span>}
    </span>
  )
}

/** 값이 없으면(유저 정보 호출 실패 등) 지어내지 않고 '—' */
const orDash = (v: number | undefined, decimals: number) => (typeof v === 'number' ? num(v, decimals) : '—')

/** 유저 기본 정보 — /stats의 profile · fixed_metrics 값을 그대로 보여준다 */
export function ProfilePanel({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const p = t.report.profile
  const pr = data.profile
  const rank = rankLabel(pr.rank)
  const wins = Math.round(data.fixed_metrics.win_rate * data.match_count)
  const trDelta = data.delta_metrics?.tr_trend_delta

  return (
    <section className="panel overflow-hidden bg-surface" aria-label="PROFILE">
      <div className="grid grid-cols-2 gap-0.5 bg-deep md:grid-cols-3 lg:grid-cols-[200px_repeat(5,minmax(0,1fr))]">
        <div className="col-span-2 flex min-w-0 items-center gap-3 bg-cell px-4 py-[15px] md:col-span-3 lg:col-span-1">
          <RankIcon rank={pr.rank} size={68} />
          <div className="min-w-0">
            <div className="font-display text-[9.5px] font-bold tracking-[.2em] text-faint">RANK</div>
            <div className={cx('mt-[3px] font-display font-extrabold tracking-[.02em]', rank ? 'text-[26px] leading-tight text-head' : 'text-lg text-muted')}>
              {rank ?? t.report.unranked}
            </div>
            <div className="text-[11.5px] text-muted">{p.season}</div>
          </div>
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
          <BigNum value={num(pr.tr, 2)} />
        </Cell>
        <Cell k="WIN RATE" sub={p.wl(wins, data.match_count - wins)}>
          <BigNum value={(data.fixed_metrics.win_rate * 100).toFixed(1)} suffix="%" />
        </Cell>
        <Cell k="APM" sub={p.apmSub}>
          <BigNum value={orDash(pr.apm, 2)} />
        </Cell>
        <Cell k="PPS" sub={p.ppsSub}>
          <BigNum value={orDash(pr.pps, 2)} />
        </Cell>
        <Cell k="VS" sub={p.vsSub}>
          <BigNum value={orDash(pr.vs, 2)} />
        </Cell>
      </div>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 border-t-2 border-deep bg-[#0F1D29] px-[18px] py-3">
        <Strip k="GLICKO" v={num(pr.glicko, 1)} unit={p.rd(num(pr.rd, 1))} />
        <Strip k="GAMES" v={num(data.match_count)} unit={p.gamesSub} />
        {typeof trDelta === 'number' && <Caption className="text-xs lg:ml-auto">{p.note}</Caption>}
      </div>
    </section>
  )
}
