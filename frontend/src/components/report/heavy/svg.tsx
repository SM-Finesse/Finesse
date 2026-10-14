import { Fragment, useId, useLayoutEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import { niceScale, rateColor, TREND_COLOR } from '../../../lib/chart'
import { cx } from '../../../lib/cx'
import { num, TREND_MARK, type Trend } from '../../../lib/stats'

/*
 * 헤비 챕터 차트 — 프로토타입(7차)의 인라인 SVG를 그대로 옮긴 것.
 * viewBox 폭 1108에 width 100%라 좁은 화면에서는 비율 그대로 줄어든다.
 */

const W = 1108

/*
 * 좁은 화면 — viewBox 1108을 그대로 줄이면 글자가 2~3px이 되므로 이 폭 밑으로는 줄이지 않고 옆으로 넘겨 본다.
 * 높이는 고정하지 않고 폭에 비례 — 고정하면 줄어든 그림 위아래가 비어 버린다.
 */
const MIN_W = 'min-w-[820px]'
/** end — 넘겨 볼 때 처음부터 오른쪽 끝(추이 차트의 최근 경기)을 보인다 */
function Fit({ children, end }: { children: ReactNode; end?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (end && el) el.scrollLeft = el.scrollWidth - el.clientWidth
  }, [end])
  return (
    <div ref={ref} className="overflow-x-auto overflow-y-hidden">
      {children}
    </div>
  )
}

const AXIS = { fontFamily: 'Oxanium, sans-serif', fontWeight: 700, fontSize: 11, fill: '#6E7D88' } as const

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
  yfmt = (v) => num(v),
  minStep = 1,
  aria,
  onOpen,
  recent,
}: {
  data: LinePoint[]
  series: LineSeries[]
  h?: number
  every?: number
  yfmt?: (v: number) => string
  /** 눈금 간격 하한 — 소수 값 차트는 더 작게 */
  minStep?: number
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
  /* 눈금은 1·2·5 단위로 떨어지게 — 14645 · 13812 같은 어중간한 값 대신 11,000 · 12,000 (라이트 TR 차트와 같은 규칙) */
  const lo0 = Math.min(...baseVals)
  const hi0 = Math.max(...baseVals)
  const pad = (hi0 - lo0) * 0.06
  const scale = niceScale(lo0 - pad, hi0 + pad, 4, minStep)
  const [mn, mx] = scale.domain
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
    <Fit end>
      <div className={cx('relative mt-0.5', MIN_W)}>
        <svg viewBox={`0 0 ${W} ${h}`} width="100%" role="img" aria-label={aria} className="block">
          <defs>
            <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={base.fill ?? base.color} stopOpacity={0.34} />
              <stop offset="1" stopColor={base.fill ?? base.color} stopOpacity={0} />
            </linearGradient>
          </defs>
          {scale.ticks.map((t) => (
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
    </Fit>
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
 * 나 vs 상대 평균 — 지표마다 위(나)·아래(상대) 막대 두 개 (프로토타입 7차).
 * 지표끼리 단위가 달라 행마다 축을 따로 잡는다. 막대는 0선에서 시작해 양수는 오른쪽, 음수는 왼쪽으로 뻗는다 —
 * 막대 길이가 곧 절댓값이라 −19.5가 −5.5보다 길다. 0선에는 늘 눈금을 둔다.
 * 값 글자는 막대 안 0선 쪽에, 막대가 짧으면 막대 끝 바깥에.
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
    <Fit>
      <svg viewBox={`0 0 ${W} ${h}`} width="100%" role="img" aria-label={aria} className={cx('block', MIN_W)}>
        {rows.map((r, i) => {
          const y = 10 + i * rowH
          const lo = Math.min(0, r.mine, r.opp)
          const hi = Math.max(0, r.mine, r.opp)
          const a = lo * 1.18
          const b = hi * 1.18
          const X = (v: number) => x0 + ((v - a) / (b - a || 1)) * (x1 - x0)
          const zx = X(0)
          const bar = (v: number, by: number, fill: string, ink: string) => {
            const width = Math.max(Math.abs(X(v) - zx), 2)
            const neg = v < 0
            const inside = width >= minInside
            /* 막대 안이면 0선 쪽 끝에서 8px, 바깥이면 막대 끝에서 7px — 음수는 왼쪽으로 */
            const tx = inside ? (neg ? zx - 8 : zx + 8) : neg ? zx - width - 7 : zx + width + 7
            return (
              <g>
                <rect x={neg ? zx - width : zx} y={by} width={width} height={18} rx={3} fill={fill} />
                <text x={tx} y={by + 13.5} textAnchor={neg ? 'end' : 'start'} {...AXIS} fontSize={12.5} fill={inside ? ink : '#C7D5E0'}>
                  {`${neg ? '−' : ''}${Math.abs(v).toFixed(r.decimals)}`}
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
              {/* 0 기준선 — 양수뿐인 행도 막대가 0에서 시작한다는 걸 보이게 늘 그린다 */}
              <g>
                <line x1={zx} y1={y} x2={zx} y2={y + 50} stroke="#8F98A0" strokeDasharray="2 3" />
                <text x={zx} y={y - 2} textAnchor="middle" {...AXIS} fontSize={11} fill="#8F98A0">
                  0
                </text>
              </g>
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
    </Fit>
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

/* 블록 막대 — 위는 밝게 · 아래는 어둡게 깎고 26px마다 홈 (SVG 막대와 같은 모양) */
const BLOCK_BAR = {
  backgroundImage: 'repeating-linear-gradient(90deg, transparent 0 24px, rgba(22,32,45,.5) 24px 26px)',
  boxShadow: 'inset 0 3px 0 rgba(255,255,255,.24), inset 0 -3px 0 rgba(0,0,0,.28)',
}

/**
 * 0을 가운데 둔 좌우 막대 — max를 끝으로, ±max/2에 보조선.
 * 다른 차트처럼 옆으로 넘기면 이름이나 한쪽 막대가 가려지므로, SVG가 아니라 HTML 격자로 그려 폭에 맞게 줄어든다.
 * 막대 칸 양옆은 값 글자 자리로 비워 둔다.
 */
export function DivergingBars({ items, max, aria, tick = (v) => String(v) }: { items: DivergingItem[]; max: number; aria: string; tick?: (v: number) => string }) {
  const track = 'relative mx-[50px] sm:mx-[64px]'
  return (
    <div role="img" aria-label={aria} className="grid grid-cols-[minmax(72px,auto)_minmax(0,1fr)] gap-x-3 pt-1 sm:grid-cols-[180px_minmax(0,1fr)]">
      {items.map((it) => {
        const has = typeof it.v === 'number'
        const v = it.v ?? 0
        /* 막대 칸 폭의 % — 한쪽 끝(max)이 50% */
        const w = Math.min(1, Math.abs(v) / (max || 1)) * 50
        const side = v >= 0 ? 'left' : 'right'
        const trend = it.trend ?? 'even'
        return (
          <Fragment key={it.k}>
            <div className="flex flex-col justify-center leading-tight">
              <b className="text-[13px] font-semibold text-ink">{it.k}</b>
              <i className="text-[11px] text-faint not-italic">{it.kr}</i>
            </div>
            <div className={cx(track, 'h-[52px]')}>
              {[0, 25, 75, 100].map((g) => (
                <i key={g} aria-hidden="true" className="absolute inset-y-0 w-px bg-line-soft" style={{ left: `${g}%` }} />
              ))}
              <i aria-hidden="true" className="absolute inset-y-0 left-1/2 w-px bg-line" />
              {has ? (
                <>
                  <i aria-hidden="true" className="absolute top-1/2 h-[22px] -translate-y-1/2 rounded-[2px]" style={{ [side]: '50%', width: `max(${w}%, 2px)`, backgroundColor: it.c, ...BLOCK_BAR }} />
                  <span className="absolute top-1/2 -translate-y-1/2 font-num text-[13px] font-bold whitespace-nowrap" style={{ [side]: `calc(50% + ${w}% + 8px)`, color: TREND_COLOR[trend] }}>
                    {`${it.text ?? ''} ${TREND_MARK[trend]}`}
                  </span>
                </>
              ) : (
                <span className="absolute top-1/2 left-[calc(50%+10px)] -translate-y-1/2 font-num text-xs text-faint">—</span>
              )}
            </div>
          </Fragment>
        )
      })}
      <div />
      <div className={cx(track, 'h-6')}>
        {[-1, -0.5, 0, 0.5, 1].map((f) => (
          <span
            key={f}
            className={cx('absolute top-1 -translate-x-1/2 font-num text-[11px] font-bold', f === 0 ? 'text-[#6E7D88]' : 'text-[#3B5A72]', Math.abs(f) === 0.5 && 'max-sm:hidden')}
            style={{ left: `${(f + 1) * 50}%` }}
          >
            {f === 0 ? '0' : (f > 0 ? '+' : '−') + tick(max * Math.abs(f))}
          </span>
        ))}
      </div>
    </div>
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
    <Fit>
      <svg
        viewBox={`0 0 ${W} ${h}`}
        width="100%"
        role="img"
        aria-label={aria}
        className={cx('block', MIN_W, onOpen && 'cursor-zoom-in')}
        onClick={onOpen}
      >
        {/* 50%축은 10 단위(0·10·…·50), 100%축은 25 단위 — 12.5 · 37.5처럼 반올림된 눈금이 나오지 않게 */}
        {(max % 4 === 0 ? [0, 0.25, 0.5, 0.75, 1] : [0, 0.2, 0.4, 0.6, 0.8, 1]).map((f) => {
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
    </Fit>
  )
}
