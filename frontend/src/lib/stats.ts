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

/**
 * 없을 수 있는 값 — 빠졌거나 음수면 지어내지 않고 '—'.
 * TETR.IO는 랭크 기록이 없는 유저의 tr·glicko·rd를 -1로 주고, 백엔드는 그대로 전달한다.
 */
export const numOrDash = (v: number | undefined, decimals = 0) => (typeof v === 'number' && v >= 0 ? num(v, decimals) : '—')

/** 이만큼 경기가 쌓여야 분석(Δ 지표·AI 코멘트)을 시작한다 — 백엔드 cold_start 기준과 같다 */
export const COLD_START_GAMES = 10

export const pct = (ratio: number, decimals = 1) => `${(ratio * 100).toFixed(decimals)}%`

/**
 * TETR.IO 랭크 문자. 'z'는 언랭크(RD 100 이상)라 '?'로 쓴다 — 표본 부족이 아니고 데이터도 정상이다(수집 명세 6.1).
 * 값이 안 왔으면 null — 부르는 쪽이 '—'로 둔다.
 */
export function rankLabel(rank: string | undefined): string | null {
  if (!rank) return null
  return rank === 'z' ? '?' : rank.toUpperCase()
}

/**
 * TETR.IO 레벨 — XP로 계산한다(TETR.IO 클라이언트와 같은 식).
 * level의 정수부가 표시 레벨, 소수부가 다음 레벨까지 진행률.
 */
export function levelFromXp(xp: number): { level: number; progress: number } {
  const raw = (xp / 500) ** 0.6 + xp / (5000 + Math.max(0, xp - 4_000_000) / 5000) + 1
  return { level: Math.floor(raw), progress: raw % 1 }
}

const REL_STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

/** '5년 전' · '12분 전' 처럼 지금과의 차이. 1분 안쪽은 '지금' */
export function timeAgo(iso: string, lang: Lang, now = Date.now()): string | null {
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return null
  const sec = Math.max(0, (now - t) / 1000)
  const fmt = new Intl.RelativeTimeFormat(lang, { numeric: 'auto' })
  for (const [unit, size] of REL_STEPS) {
    if (sec >= size) return fmt.format(-Math.floor(sec / size), unit)
  }
  return fmt.format(0, 'second')
}

/** 국가 코드 → 이름. 'XM'처럼 표준에 없는 코드는 코드 그대로 */
export function countryName(code: string, lang: Lang): string {
  try {
    return new Intl.DisplayNames([lang], { type: 'region', fallback: 'code' }).of(code.toUpperCase()) ?? code
  } catch {
    return code
  }
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
  | 'comeback_rate_against'
  | 'delta_comeback'
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
    code: 'ΔPlonk', label: { ko: '효율 중시', en: 'Plonk' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_plonk,
  },
  delta_stride: {
    code: 'ΔStride', label: { ko: '지속 전개', en: 'Stride' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_stride,
  },
  delta_inf_ds: {
    code: 'ΔInf DS', label: { ko: '가비지 처리 중심', en: 'Inf DS' }, kind: 'delta', decimals: 2,
    pick: (d) => d.playstyle_relative?.delta_inf_ds,
  },
  delta_app: {
    code: 'ΔAPP', label: { ko: '공격 효율', en: 'Attack efficiency' }, kind: 'delta', decimals: 3,
    pick: (d) => d.attack?.delta_app,
  },
  delta_weighted_app: {
    code: 'ΔWeighted APP', label: { ko: '공격 성향', en: 'Attack tendency' }, kind: 'delta', decimals: 3,
    pick: (d) => d.attack?.delta_weighted_app,
  },
  delta_vs_apm: {
    code: 'ΔVS/APM', label: { ko: '공격 대비 방어 비율', en: 'Defense-to-attack ratio' }, kind: 'delta', decimals: 3,
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
  comeback_rate_against: {
    code: 'Blown Lead', label: { ko: '역전패율', en: 'Blown-lead rate' }, kind: 'rate', decimals: 1, unit: '%',
    pick: (d) => d.comeback_rate_against,
  },
  delta_comeback: {
    code: 'ΔComeback', label: { ko: '역전승률 − 역전 허용률', en: 'Comeback − blown lead' }, kind: 'gap', decimals: 1, unit: '%p',
    pick: (d) => d.delta_comeback,
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

/**
 * 라이트 하이라이트 후보 11개 (기능 명세 3.4절 · v1.2). tr_trend_delta는 고정 지표 'TR 추이'와 겹치고,
 * 역전은 두 비율의 차이(delta_comeback)만 후보다 — comeback_rate·comeback_rate_against는 헤비 뷰 전용이라 오면 매핑 실패로 뺀다.
 */
export const HIGHLIGHT_CANDIDATES: ReadonlySet<StatKey> = new Set<StatKey>([
  'delta_opener',
  'delta_plonk',
  'delta_stride',
  'delta_inf_ds',
  'delta_app',
  'delta_weighted_app',
  'delta_vs_apm',
  'delta_cheese_index',
  'strength_split',
  'delta_comeback',
  'session_vs_slope',
])

export interface HighlightItem {
  sentence: string
  ev: Evidence
}

/**
 * LLM이 고른 하이라이트에 근거 수치를 붙인다(FR-05).
 * 후보가 아니거나, 존재하지 않거나, 계산되지 않은 지표를 가리키는 항목은 매핑 실패로 보고 그 항목만 뺀다. 같은 지표가 겹치면 처음 것만.
 */
export function mapHighlights(highlights: LightCommentResponse['highlights'], delta: DeltaMetrics | undefined): HighlightItem[] {
  const out: HighlightItem[] = []
  for (const h of highlights) {
    if (!HIGHLIGHT_CANDIDATES.has(h.stat as StatKey)) continue
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
