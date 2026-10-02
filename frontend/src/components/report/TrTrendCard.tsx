import { Area, AreaChart, CartesianGrid, Tooltip, XAxis, YAxis } from 'recharts'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { num, numOrDash, signed, trendOf } from '../../lib/stats'
import { BigNum, Caption, DeltaPill, Notice, PanelHead } from './parts'

const AXIS_TICK = { fill: '#6E7D88', fontSize: 11, fontFamily: 'Oxanium, sans-serif', fontWeight: 700 }

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

  const points = series.map((v, i) => ({ game: i + 1, tr: v }))
  const hi = Math.max(...series)
  const lo = Math.min(...series)
  const pad = (hi - lo) * 0.18 || 1
  const change = series[series.length - 1] - series[0]

  return (
    <section className="panel bg-surface p-[22px]" aria-label={tr.title}>
      <PanelHead tag="TR TREND" title={tr.title} right={right} />
      <p className="num-hud -mt-1 mb-2 text-[30px] text-white">
        <BigNum value={numOrDash(data.profile.tr, 2)} />
      </p>

      <div data-testid="tr-chart">
        <AreaChart responsive data={points} style={{ width: '100%', height: 172 }} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id="trFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#66C0F4" stopOpacity={0.34} />
              <stop offset="1" stopColor="#66C0F4" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke="#223749" />
          <XAxis dataKey="game" tick={AXIS_TICK} tickLine={false} axisLine={{ stroke: '#2A475E' }} minTickGap={28} />
          <YAxis
            domain={[lo - pad, hi + pad]}
            tick={AXIS_TICK}
            tickLine={false}
            axisLine={false}
            width={52}
            tickCount={5}
            tickFormatter={(v: number) => num(v)}
          />
          <Tooltip
            isAnimationActive={false}
            cursor={{ stroke: '#66C0F4', strokeDasharray: '3 3' }}
            content={({ active, payload }) => {
              const p = payload?.[0]?.payload as { game: number; tr: number } | undefined
              if (!active || !p) return null
              return (
                <div className="min-w-[132px] rounded-md border border-line bg-surface-2 px-3 py-2.5 text-xs shadow-[0_12px_28px_-10px_rgba(0,0,0,.85)]">
                  <div className="mb-1.5 font-mono text-[11px] text-faint">GAME {p.game}</div>
                  <div className="flex justify-between gap-3.5">
                    <span>TR</span>
                    <b className="font-num tabular-nums">{num(p.tr, 1)}</b>
                  </div>
                </div>
              )
            }}
          />
          <Area
            type="monotone"
            dataKey="tr"
            stroke="#66C0F4"
            strokeWidth={2}
            fill="url(#trFill)"
            dot={false}
            activeDot={{ r: 4, fill: '#66C0F4', stroke: '#fff', strokeWidth: 1.5 }}
            isAnimationActive={false}
          />
        </AreaChart>
      </div>

      <div className="mt-[18px] flex flex-wrap gap-x-[34px] gap-y-3 border-t border-line-soft pt-4">
        <Stat k={tr.high} v={num(hi, 1)} />
        <Stat k={tr.low} v={num(lo, 1)} />
        <Stat k={tr.change} v={signed(change, 1)} />
        <Stat k={tr.perGame} v={signed(change / (series.length - 1), 2)} />
      </div>
    </section>
  )
}
