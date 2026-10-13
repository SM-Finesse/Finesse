import { useId } from 'react'
import { Area, AreaChart, CartesianGrid, Tooltip, XAxis, YAxis } from 'recharts'
import { AXIS_TICK } from '../../lib/chart'
import { num } from '../../lib/stats'

/** TR 추이 영역 차트 — series는 과거→현재, 경기 단위. 값이 2개 이상일 때만 부른다 */
export function TrChart({ series, height }: { series: number[]; height: number }) {
  const fill = `trFill${useId().replace(/[^\w-]/g, '')}`
  const points = series.map((v, i) => ({ game: i + 1, tr: v }))
  const hi = Math.max(...series)
  const lo = Math.min(...series)
  const pad = (hi - lo) * 0.18 || 1

  return (
    <div data-testid="tr-chart">
      <AreaChart responsive data={points} style={{ width: '100%', height }} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={fill} x1="0" y1="0" x2="0" y2="1">
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
          fill={`url(#${fill})`}
          dot={false}
          activeDot={{ r: 4, fill: '#66C0F4', stroke: '#fff', strokeWidth: 1.5 }}
          isAnimationActive={false}
        />
      </AreaChart>
    </div>
  )
}
