import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { num, numOrDash, signed, trendOf } from '../../lib/stats'
import { BigNum, Caption, DeltaPill, Notice, PanelHead } from './parts'
import { TrChart } from './TrChart'

function Stat({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <div className="font-mono text-[10px] font-bold tracking-[.2em] text-faint">{k}</div>
      <div className="mt-1 font-num text-[19px] font-bold text-head tabular-nums">
        <BigNum value={v} />
      </div>
    </div>
  )
}

/** TR 추이 — fixed_metrics.tr_trend(과거→현재, 경기 단위)를 그대로 그린다 */
export function TrTrendCard({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const tr = t.report.tr
  const series = data.fixed_metrics.tr_trend
  const delta = data.delta_metrics?.tr_trend_delta
  const right = (
    <>
      <Caption>{tr.games(series.length || data.match_count)}</Caption>
      {typeof delta === 'number' && <DeltaPill text={signed(delta, 1)} trend={trendOf(delta, 1)} />}
    </>
  )

  /* 표본 부족(콜드스타트)이면 이 카드는 그리지 않는다(LightView). 여기서는 경기는 충분한데 값이 안 온 경우만 안내 */
  if (series.length < 2) {
    return (
      <section className="panel bg-surface p-[22px]" aria-label={tr.title}>
        <PanelHead tag="TR TREND" title={tr.title} right={<Caption>{tr.games(data.match_count)}</Caption>} />
        <p className="num-hud -mt-1 mb-0 text-[30px] text-white">
          <BigNum value={numOrDash(data.profile.tr, 2)} />
        </p>
        <div className="mt-3.5">
          <Notice title={tr.missingTitle}>{tr.missingBody}</Notice>
        </div>
      </section>
    )
  }

  const hi = Math.max(...series)
  const lo = Math.min(...series)
  const change = series[series.length - 1] - series[0]

  return (
    <section className="panel bg-surface p-[22px]" aria-label={tr.title}>
      <PanelHead tag="TR TREND" title={tr.title} right={right} />
      <p className="num-hud -mt-1 mb-2 text-[30px] text-white">
        <BigNum value={numOrDash(data.profile.tr, 2)} />
      </p>

      <TrChart series={series} height={172} />

      <div className="mt-[18px] flex flex-wrap gap-x-[34px] gap-y-3 border-t border-line-soft pt-4">
        <Stat k={tr.high} v={num(hi, 1)} />
        <Stat k={tr.low} v={num(lo, 1)} />
        <Stat k={tr.change} v={signed(change, 1)} />
        <Stat k={tr.perGame} v={signed(change / (series.length - 1), 2)} />
      </div>
    </section>
  )
}
