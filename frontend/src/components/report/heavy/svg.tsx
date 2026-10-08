import { useId, useState, type MouseEvent } from 'react'
import { rateColor, TREND_COLOR } from '../../../lib/chart'
import { cx } from '../../../lib/cx'
import { TREND_MARK, type Trend } from '../../../lib/stats'

/*
 * 헤비 챕터 차트 — 프로토타입(7차)의 인라인 SVG를 그대로 옮긴 것.
 * viewBox 폭 1108에 width 100%라 좁은 화면에서는 비율 그대로 줄어든다.
 */

const W = 1108
const AXIS = { fontFamily: 'Oxanium, sans-serif', fontWeight: 700, fontSize: 11, fill: '#6E7D88' } as const

const ticks = (min: number, max: number, n: number) => Array.from({ length: n + 1 }, (_, i) => min + ((max - min) / n) * i)

export interface LineSeries {
  key: string
  label: string
  color: string
  /** 첫 줄만 — 아래를 채우는 그라데이션 색 */
  fill?: string
  /** 자기 범위 안에서 0~1로 눈금을 맞춘 선 */
  norm?: boolean
  dash?: boolean
  /** 툴팁 값 표기 */
  fmt?: (v: number) => string
}

export interface LinePoint {
  /** x축 글자 */
  label: string
  /** 툴팁 머리 */
  head: string
  values: Record<string, number>
}

/** 최근 구간 강조 — from은 점 위치(소수 가능), 그 오른쪽 끝까지 */
export interface RecentBand {
  from: number
  label: string
}

export const RECENT_COLOR = '#8FC93A'

/** 추이 선 차트 — 첫 줄이 기준(실제 값), 나머지는 정규화 점선. 마우스를 올리면 그 지점 값을, 누르면 onOpen. recent면 그 구간을 초록으로 */
export function LineChart({
  data,
  series,
  h = 300,
  every = 1,
  yfmt = (v) => String(Math.round(v)),
  aria,
  onOpen,
  recent,
}: {
  data: LinePoint[]
  series: LineSeries[]
  h?: number
  every?: number
  yfmt?: (v: number) => string
  aria: string
  onOpen?: () => void
  recent?: RecentBand
}) {
  const id = `ln${useId().replace(/[^\w-]/g, '')}`
  const [hover, setHover] = useState<number | null>(null)
  const m = { l: 52, r: 18, t: 16, b: 34 }
  const iw = W - m.l - m.r
  const ih = h - m.t - m.b
  const n = data.length
  const base = series[0]
  const baseVals = data.map((d) => d.values[base.key])
  let mn = Math.min(...baseVals)
  let mx = Math.max(...baseVals)
  const pad = (mx - mn) * 0.18 || 1
  mn -= pad
  mx += pad
  const X = (i: number) => m.l + (n === 1 ? iw / 2 : (iw * i) / (n - 1))
  const Y = (v: number) => m.t + ih - ((v - mn) / (mx - mn)) * ih

  const lines = series.map((sr) => {
    const vs = data.map((d) => d.values[sr.key])
    const lo = Math.min(...vs)
    const hi = Math.max(...vs)
    const yy = (v: number) => (sr.norm ? m.t + ih - ((v - lo) / (hi - lo || 1)) * ih * 0.72 - ih * 0.14 : Y(v))
    return { sr, pts: data.map((d, i) => `${X(i).toFixed(1)} ${yy(d.values[sr.key]).toFixed(1)}`), yy }
  })

  const move = (e: MouseEvent<SVGRectElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    if (!r.width || n < 1) return
    const ratio = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width))
    setHover(Math.round(ratio * (n - 1)))
  }

  const tipLeft = hover === null ? 0 : Math.min(88, Math.max(12, (X(hover) / W) * 100))

  /* 최근 구간 — 경계(소수 위치)에서 선을 끊어 그 뒤를 초록으로 다시 그린다 */
  const band = (() => {
    if (!recent || n < 2) return null
    const from = Math.min(n - 1, Math.max(0, recent.from))
    const yy = lines[0].yy
    const i0 = Math.floor(from)
    const t = from - i0
    const v0 = data[i0].values[base.key]
    const v1 = data[Math.min(n - 1, i0 + 1)].values[base.key]
    const start = `${(X(i0) + (X(Math.min(n - 1, i0 + 1)) - X(i0)) * t).toFixed(1)} ${yy(v0 + (v1 - v0) * t).toFixed(1)}`
    const rest = data.map((d, i) => (i > from ? `${X(i).toFixed(1)} ${yy(d.values[base.key]).toFixed(1)}` : null)).filter(Boolean)
    return { x: X(0) + (X(n - 1) - X(0)) * (from / (n - 1)), d: `M ${[start, ...rest].join(' L ')}` }
  })()

  return (
    <div className="relative mt-0.5">
      <svg viewBox={`0 0 ${W} ${h}`} width="100%" height={h} role="img" aria-label={aria} className="block">
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={base.fill ?? base.color} stopOpacity={0.34} />
            <stop offset="1" stopColor={base.fill ?? base.color} stopOpacity={0} />
          </linearGradient>
        </defs>
        {ticks(mn, mx, 4).map((t) => (
          <g key={t}>
            <line x1={m.l} y1={Y(t)} x2={W - m.r} y2={Y(t)} stroke="#223749" />
            <text x={m.l - 10} y={Y(t) + 4} textAnchor="end" {...AXIS}>
              {yfmt(t)}
            </text>
          </g>
        ))}
        {data.map((d, i) =>
          i % every === 0 || i === n - 1 ? (
            <text key={i} x={X(i)} y={h - 12} textAnchor="middle" {...AXIS}>
              {d.label}
            </text>
          ) : null,
        )}
        {band && recent && (
          <g>
            <rect x={band.x} y={m.t} width={m.l + iw - band.x} height={ih} fill={RECENT_COLOR} opacity={0.08} />
            <line x1={band.x} y1={m.t} x2={band.x} y2={m.t + ih} stroke={RECENT_COLOR} strokeDasharray="3 3" opacity={0.6} />
            <text x={band.x + 6} y={m.t + 13} {...AXIS} fill={RECENT_COLOR}>
              {recent.label}
            </text>
          </g>
        )}
        {lines.map(({ sr, pts, yy }, si) => (
          <g key={sr.key}>
            {si === 0 && <path d={`M ${pts.join(' L ')} L ${X(n - 1).toFixed(1)} ${m.t + ih} L ${X(0).toFixed(1)} ${m.t + ih} Z`} fill={`url(#${id})`} />}
            <path
              d={`M ${pts.join(' L ')}`}
              fill="none"
              stroke={sr.color}
              strokeWidth={sr.norm ? 1.6 : 2.4}
              strokeLinejoin="round"
              strokeLinecap="round"
              strokeDasharray={sr.dash ? '5 5' : undefined}
            />
            {si === 0 && band && <path d={band.d} fill="none" stroke={RECENT_COLOR} strokeWidth={2.6} strokeLinejoin="round" strokeLinecap="round" />}
            {!sr.norm && <circle cx={X(n - 1)} cy={yy(data[n - 1].values[sr.key])} r={4} fill={si === 0 && band ? RECENT_COLOR : sr.color} />}
          </g>
        ))}
        <line x1={m.l} y1={m.t + ih} x2={W - m.r} y2={m.t + ih} stroke="#2A475E" />
        {hover !== null && <line x1={X(hover)} y1={m.t} x2={X(hover)} y2={m.t + ih} stroke="#66C0F4" strokeDasharray="3 3" />}
        <rect
          x={m.l}
          y={m.t}
          width={iw}
          height={ih}
          fill="transparent"
          className={onOpen ? 'cursor-zoom-in' : undefined}
          onMouseMove={move}
          onMouseLeave={() => setHover(null)}
          onClick={onOpen}
        />
      </svg>
      {hover !== null && (
        <div
          className="pointer-events-none absolute top-1 w-max max-w-[280px] -translate-x-1/2 rounded-md border border-line bg-surface-2 px-3 py-2.5 text-xs shadow-[0_12px_28px_-10px_rgba(0,0,0,.85)] backdrop-blur-sm"
          style={{ left: `${tipLeft}%` }}
        >
          <div className="mb-1.5 font-mono text-[11px] text-faint">{data[hover].head}</div>
          {series.map((sr) => (
            <div key={sr.key} className="flex justify-between gap-3.5 leading-[1.7]">
              <span style={{ color: sr.color }}>{sr.key.toUpperCase()}</span>
              <b className="font-num font-bold tabular-nums">{(sr.fmt ?? String)(data[hover].values[sr.key])}</b>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 범례 — 실선/점선 */
export interface CompareRow {
  k: string
  kr: string
  mine: number
  opp: number
  decimals: number
  /** 오른쪽 끝 — 칸에 찍힌 Δ와 같은 글자 */
  delta: string
  trend: Trend
}

/**
 * 나 vs 상대 평균 — 지표마다 위(나)·아래(상대) 막대 두 개, 값은 막대 시작 쪽 안에 (프로토타입 7차).
 * 지표끼리 단위가 달라 행마다 축을 따로 잡는다. 막대는 모두 왼쪽에서 시작하고 값이 클수록 길다 —
 * 양수만 있으면 0부터(큰 쪽 × 1.18), Cheese Index처럼 음수가 끼면 가장 작은 값보다 조금 아래부터 잡아
 * −6.5가 −20.7보다 길게 보이도록 한다. 이때 0 위치에 눈금을 남긴다.
 */
export function CompareBars({ rows, me, opp, aria }: { rows: CompareRow[]; me: string; opp: string; aria: string }) {
  const rowH = 76
  const h = rows.length * rowH + 22
  const labW = 210
  const valW = 150
  const x0 = labW
  const x1 = W - valW
  /* 막대가 이보다 짧으면 값 글자를 막대 밖에 둔다 */
  const minInside = 52

  return (
    <svg viewBox={`0 0 ${W} ${h}`} width="100%" height={h} role="img" aria-label={aria} className="block">
      {rows.map((r, i) => {
        const y = 10 + i * rowH
        const lo = Math.min(0, r.mine, r.opp)
        const hi = Math.max(0, r.mine, r.opp)
        const a = lo * 1.18
        const b = hi * 1.18
        const X = (v: number) => x0 + ((v - a) / (b - a || 1)) * (x1 - x0)
        const bar = (v: number, by: number, fill: string, ink: string) => {
          const width = Math.max(X(v) - x0, 2)
          const inside = width >= minInside
          /* 값 글자 — 막대가 충분히 길면 막대 안 왼쪽에, 짧으면 막대 끝 바깥에 */
          return (
            <g>
              <rect x={x0} y={by} width={width} height={18} rx={3} fill={fill} />
              <text x={inside ? x0 + 8 : x0 + width + 7} y={by + 13} {...AXIS} fill={inside ? ink : '#C7D5E0'}>
                {`${v < 0 ? '−' : ''}${Math.abs(v).toFixed(r.decimals)}`}
              </text>
            </g>
          )
        }
        return (
          <g key={r.k}>
            <text x={0} y={y + 18} fontSize={13} fontWeight={600} fill="#C7D5E0">
              {r.k}
            </text>
            <text x={0} y={y + 34} fontSize={11} fill="#6E7D88">
              {r.kr}
            </text>
            {bar(r.mine, y + 4, TREND_COLOR[r.trend], '#0B1218')}
            {bar(r.opp, y + 28, '#2A475E', '#C7D5E0')}
            {lo < 0 && (
              <g>
                <line x1={X(0)} y1={y} x2={X(0)} y2={y + 50} stroke="#6E7D88" strokeDasharray="2 3" />
                <text x={X(0)} y={y - 2} textAnchor="middle" {...AXIS} fontSize={10}>
                  0
                </text>
              </g>
            )}
            <text x={W} y={y + 30} textAnchor="end" {...AXIS} fontSize={16} fill={TREND_COLOR[r.trend]}>
              {`${r.delta} ${TREND_MARK[r.trend]}`}
            </text>
          </g>
        )
      })}
      <g transform={`translate(${labW},${h - 4})`}>
        <rect x={0} y={-9} width={10} height={10} rx={2} fill="#8F98A0" />
        <text x={16} y={0} fontSize={11} fill="#A5A9C4">
          {me}
        </text>
        <rect x={64} y={-9} width={10} height={10} rx={2} fill="#2A475E" />
        <text x={80} y={0} fontSize={11} fill="#A5A9C4">
          {opp}
        </text>
      </g>
    </svg>
  )
}

export function Legend({ items }: { items: { label: string; color: string; dash?: boolean }[] }) {
  return (
    <div className="mt-2.5 flex flex-wrap gap-[18px] pl-0.5">
      {items.map((s) => (
        <span key={s.label} className="inline-flex items-center gap-[7px] text-[11.5px] text-muted">
          <i className="inline-block h-0 w-3.5 border-t-2" style={{ borderTopColor: s.color, borderTopStyle: s.dash ? 'dashed' : 'solid' }} />
          {s.label}
        </span>
      ))}
    </div>
  )
}

export interface DivergingItem {
  k: string
  kr: string
  /** 없으면 '—' (계산 전) */
  v?: number
  c: string
  text?: string
  trend?: Trend
}

/** 0을 가운데 둔 좌우 막대 — max를 끝으로, ±max/2에 보조선 */
export function DivergingBars({ items, max, aria, tick = (v) => String(v) }: { items: DivergingItem[]; max: number; aria: string; tick?: (v: number) => string }) {
  const rowH = 52
  const H = items.length * rowH + 30
  const labW = 180
  const valW = 96
  const zx = labW + (W - labW - valW) / 2
  const half = (W - labW - valW) / 2 - 14

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={aria} className="block">
      <line x1={zx} y1={6} x2={zx} y2={H - 24} stroke="#2A475E" />
      <text x={zx} y={H - 8} textAnchor="middle" {...AXIS}>
        0
      </text>
      {[0.5, 1].flatMap((f) =>
        [1, -1].map((d) => {
          const gx = zx + d * half * f
          return (
            <g key={`${f}${d}`}>
              <line x1={gx} y1={6} x2={gx} y2={H - 24} stroke="#223749" />
              <text x={gx} y={H - 8} textAnchor="middle" {...AXIS} fill="#2A475E">
                {(d > 0 ? '+' : '−') + tick(max * f)}
              </text>
            </g>
          )
        }),
      )}
      {items.map((it, i) => {
        const y = 16 + i * rowH
        const has = typeof it.v === 'number'
        const v = it.v ?? 0
        const w = Math.min(half, (Math.abs(v) / (max || 1)) * half)
        const x = v >= 0 ? zx : zx - w
        const bw = Math.max(w, 2)
        const trend = it.trend ?? 'even'
        const tx = v >= 0 ? zx + w + 10 : zx - w - 10
        return (
          <g key={it.k}>
            <text x={0} y={y + 20} fontSize={13} fontWeight={600} fill="#C7D5E0">
              {it.k}
            </text>
            <text x={0} y={y + 36} fontSize={11} fill="#6E7D88">
              {it.kr}
            </text>
            {has ? (
              <>
                <rect x={x} y={y + 8} width={bw} height={22} rx={2} fill={it.c} />
                <rect x={x} y={y + 8} width={bw} height={3} fill="#FFFFFF" opacity={0.24} />
                <rect x={x} y={y + 27} width={bw} height={3} fill="#000000" opacity={0.28} />
                {Array.from({ length: Math.max(0, Math.ceil(bw / 26) - 1) }, (_, q) => (
                  <rect key={q} x={x + (q + 1) * 26} y={y + 8} width={2} height={22} fill="#16202D" opacity={0.5} />
                ))}
                <text x={tx} y={y + 24} textAnchor={v >= 0 ? 'start' : 'end'} {...AXIS} fontSize={13} fill={TREND_COLOR[trend]}>
                  {`${it.text ?? ''} ${TREND_MARK[trend]}`}
                </text>
              </>
            ) : (
              <text x={zx + 10} y={y + 24} {...AXIS} fontSize={12}>
                —
              </text>
            )}
          </g>
        )
      })}
    </svg>
  )
}

export interface ColumnItem {
  k: string
  /** 아래 둘째 줄 — 방향과 뜻 */
  s?: string
  v: number
  c?: string
  /** 값·둘째 줄 글자색 */
  vc?: string
}

/** 블록을 쌓아 올린 세로 막대 — 값은 %. 막대가 셋 이상이면 꼭대기를 점선으로 이어 흐름을 보이고, 누르면 onOpen */
export function ColumnChart({
  items,
  h = 276,
  mb = 46,
  max = 100,
  bw: bwMax = 96,
  note,
  aria,
  trend = items.length > 2,
  onOpen,
}: {
  items: ColumnItem[]
  h?: number
  mb?: number
  max?: number
  bw?: number
  note?: string
  aria: string
  trend?: boolean
  onOpen?: () => void
}) {
  const m = { l: 48, r: 16, t: 16, b: mb }
  const iw = W - m.l - m.r
  const ih = h - m.t - m.b
  const slot = iw / items.length
  const bw = Math.min(bwMax, slot - 46)
  const cellH = 14
  const gap = 3
  const cxOf = (i: number) => m.l + slot * i + slot / 2
  const cellsOf = (v: number) => (v > 0 ? Math.max(1, Math.floor(((Math.min(v, max) / max) * ih + gap) / (cellH + gap))) : 0)
  /* 실제로 쌓인 칸의 꼭대기 — 값 글자와 점선이 같은 높이를 쓴다 */
  const topOf = (v: number) => {
    const cells = cellsOf(v)
    return m.t + ih - cells * (cellH + gap) + (cells ? gap : 0)
  }

  return (
    <svg
      viewBox={`0 0 ${W} ${h}`}
      width="100%"
      height={h}
      role="img"
      aria-label={aria}
      className={cx('block', onOpen && 'cursor-zoom-in')}
      onClick={onOpen}
    >
      {[0, 0.25, 0.5, 0.75, 1].map((f) => {
        const y = m.t + ih - f * ih
        return (
          <g key={f}>
            <line x1={m.l} y1={y} x2={W - m.r} y2={y} stroke="#223749" />
            <text x={m.l - 10} y={y + 4} textAnchor="end" {...AXIS}>
              {(max * f).toFixed(0)}
            </text>
          </g>
        )
      })}
      {items.map((it, i) => {
        const cx = cxOf(i)
        const c = it.c ?? rateColor(it.v)
        /* 0%면 막대를 그리지 않는다. 0보다 크면 아무리 작아도 한 칸은 보이게 */
        const cells = cellsOf(it.v)
        /* 값 글자는 실제로 쌓인 칸 위에 — 한 칸이 값보다 높게 그려져도 겹치지 않게 */
        const top = topOf(it.v)
        return (
          <g key={it.k}>
            {Array.from({ length: cells }, (_, k) => {
              const cy = m.t + ih - (k + 1) * (cellH + gap) + gap
              return (
                <g key={k}>
                  <rect x={cx - bw / 2} y={cy} width={bw} height={cellH} rx={2} fill={c} />
                  <rect x={cx - bw / 2} y={cy} width={bw} height={2.5} fill="#FFFFFF" opacity={0.24} />
                  <rect x={cx - bw / 2} y={cy + cellH - 2.5} width={bw} height={2.5} fill="#000000" opacity={0.28} />
                </g>
              )
            })}
            <text x={cx} y={top - 9} textAnchor="middle" {...AXIS} fontSize={14} fill={it.vc ?? '#C7D5E0'}>
              {`${it.v.toFixed(1)}%`}
            </text>
            <text x={cx} y={m.t + ih + 20} textAnchor="middle" fontSize={12} fill="#8F98A0">
              {it.k}
            </text>
            {it.s && (
              <text x={cx} y={m.t + ih + 38} textAnchor="middle" {...AXIS} fill={it.vc ?? '#8F98A0'}>
                {it.s}
              </text>
            )}
          </g>
        )
      })}
      {trend && (
        <path
          d={`M ${items.map((it, i) => `${cxOf(i).toFixed(1)} ${topOf(it.v).toFixed(1)}`).join(' L ')}`}
          fill="none"
          stroke="#66C0F4"
          strokeWidth={1.5}
          strokeDasharray="4 4"
          opacity={0.45}
        />
      )}
      {note && (
        <text x={m.l} y={h - 8} fontSize={11} fill="#6E7D88">
          {note}
        </text>
      )}
      <line x1={m.l} y1={m.t + ih} x2={W - m.r} y2={m.t + ih} stroke="#2A475E" />
    </svg>
  )
}
