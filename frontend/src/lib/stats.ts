import type { DeltaMetrics, LightCommentResponse, StatsResponse } from '../api/types'
import type { Lang } from '../types'

/*
 * 화면 표시 규칙만 둔다 — 값 계산은 전부 백엔드 몫이고, 여기서는 받은 값을 어떻게 읽힐지만 정한다.
 */

export type Trend = 'up' | 'down' | 'even'

/** Δ 방향. 표시 자릿수에서 0으로 반올림되는 값은 '동등'으로 본다 */
export function trendOf(v: number, decimals: number): Trend {
  if (Math.abs(v) < 0.5 * 10 ** -decimals) return 'even'
  return v > 0 ? 'up' : 'down'
}

export const TREND_MARK: Record<Trend, string> = { up: '▲', down: '▼', even: '≈' }

/** 부호를 항상 붙인다. 음수는 하이픈 대신 진짜 마이너스(−) */
export function signed(v: number, decimals: number): string {
  const abs = Math.abs(v).toFixed(decimals)
  if (trendOf(v, decimals) === 'even') return `±${abs}`
  return `${v > 0 ? '+' : '−'}${abs}`
}

export const num = (v: number, decimals = 0) =>
  v.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })

export const pct = (ratio: number, decimals = 1) => `${(ratio * 100).toFixed(decimals)}%`

/** TETR.IO 랭크 문자 — 'z'는 이번 시즌 랭크가 없는 상태 */
export function rankLabel(rank: string | undefined): string | null {
  if (!rank || rank === 'z') return null
  return rank.toUpperCase()
}

/* ── 하이라이트 근거 ──────────────────────────────────────────
 * /comment의 highlights[].stat은 /stats의 delta_metrics 안 필드 이름과 1:1 (FR-05).
 * delta: 부호가 우위/열세를 뜻함 → ▲/▼ + 상태색. rate: 비율 그 자체라 방향 없음. */
export type StatKey =
  | 'tr_trend_delta'
  | 'delta_opener'
  | 'delta_plonk'
  | 'delta_stride'
  | 'delta_inf_ds'
  | 'delta_app'
  | 'delta_weighted_app'
  | 'delta_vs_apm'
  | 'delta_cheese_index'
  | 'strength_split'
  | 'comeback_rate'
  | 'session_vs_slope'

interface StatMeta {
  code: string
  label: Record<Lang, string>
  kind: 'delta' | 'rate' | 'gap'
  decimals: number
  unit?: string
  pick: (d: DeltaMetrics) => number | undefined
}

export const STAT_META: Record<StatKey, StatMeta> = {
  tr_trend_delta: {
    code: 'ΔTR', label: { ko: 'TR 흐름', en: 'TR trend' }, kind: 'delta', decimals: 1, unit: ' TR',
    pick: (d) => d.tr_trend_delta,
  },
  delta_opener: {
    code: 'ΔOpener', label: { ko: '초반 전개', en: 'Opener' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_opener,
  },
  delta_plonk: {
    code: 'ΔPlonk', label: { ko: '단순 적재', en: 'Plonk' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_plonk,
  },
  delta_stride: {
    code: 'ΔStride', label: { ko: '지속 전개', en: 'Stride' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_stride,
  },
  delta_inf_ds: {
    code: 'ΔInf DS', label: { ko: '다운스택 유지', en: 'Inf DS' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_inf_ds,
  },
  delta_app: {
    code: 'ΔAPP', label: { ko: '공격 효율', en: 'Attack efficiency' }, kind: 'delta', decimals: 3,
    pick: (d) => d.attack?.delta_app,
  },
  delta_weighted_app: {
    code: 'ΔWeighted APP', label: { ko: '가중 공격 효율', en: 'Weighted attack' }, kind: 'delta', decimals: 3,
    pick: (d) => d.attack?.delta_weighted_app,
  },
  delta_vs_apm: {
    code: 'ΔVS/APM', label: { ko: '수비 여력', en: 'Defensive headroom' }, kind: 'delta', decimals: 3,
    pick: (d) => d.defense?.delta_vs_apm,
  },
  delta_cheese_index: {
    code: 'ΔCheese Index', label: { ko: '가비지 처리', en: 'Garbage clearing' }, kind: 'delta', decimals: 1,
    pick: (d) => d.defense?.delta_cheese_index,
  },
  strength_split: {
    code: 'Strength Split', label: { ko: '상대 강도별 승률차', en: 'Strength split' }, kind: 'gap', decimals: 1, unit: '%p',
    pick: (d) => d.strength_split,
  },
  comeback_rate: {
    code: 'Comeback Rate', label: { ko: '역전승률', en: 'Comeback rate' }, kind: 'rate', decimals: 1, unit: '%',
    pick: (d) => d.comeback_rate,
  },
  session_vs_slope: {
    code: 'VS Slope', label: { ko: '경기 내 컨디션', en: 'In-game condition' }, kind: 'delta', decimals: 2, unit: '/R',
    pick: (d) => d.session_vs_slope,
  },
}

export interface Evidence {
  stat: StatKey
  meta: StatMeta
  value: number
  /** 화면에 찍을 값 — 단위 포함 */
  text: string
  /** rate는 방향이 없다 */
  trend: Trend | null
}

/** 근거 수치를 찾는다. 키가 없거나 값이 null이면 매핑 실패로 보고 null (FR-05 예외 처리) */
export function evidenceOf(stat: string, delta: DeltaMetrics | undefined): Evidence | null {
  if (!delta || !(stat in STAT_META)) return null
  const key = stat as StatKey
  const meta = STAT_META[key]
  const value = meta.pick(delta)
  if (typeof value !== 'number' || !Number.isFinite(value)) return null

  const unit = meta.unit ?? ''
  if (meta.kind === 'rate') return { stat: key, meta, value, text: `${(value * 100).toFixed(meta.decimals)}${unit}`, trend: null }
  const shown = meta.kind === 'gap' ? value * 100 : value
  return { stat: key, meta, value, text: `${signed(shown, meta.decimals)}${unit}`, trend: trendOf(shown, meta.decimals) }
}

export interface HighlightItem {
  sentence: string
  ev: Evidence
}

/**
 * LLM이 고른 하이라이트에 근거 수치를 붙인다(FR-05).
 * 존재하지 않거나 계산되지 않은 지표를 가리키는 항목은 매핑 실패로 보고 그 항목만 뺀다. 같은 지표가 겹치면 처음 것만.
 */
export function mapHighlights(highlights: LightCommentResponse['highlights'], delta: DeltaMetrics | undefined): HighlightItem[] {
  const out: HighlightItem[] = []
  for (const h of highlights) {
    const ev = evidenceOf(h.stat, delta)
    if (ev && !out.some((o) => o.ev.stat === ev.stat)) out.push({ sentence: h.sentence, ev })
  }
  return out.slice(0, 3)
}

/** 최근 경기 승패 집계 — recent_form은 index 0이 가장 최근 */
export function formSummary(form: StatsResponse['fixed_metrics']['recent_form']) {
  const wins = form.filter((r) => r === 'W').length
  return { games: form.length, wins, losses: form.length - wins, rate: form.length ? wins / form.length : 0 }
}
