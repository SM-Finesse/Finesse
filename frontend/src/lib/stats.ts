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

/**
 * 승률 증감(%p) — 백엔드 wr_delta_pct는 최근 n판 승률 R과 나머지 판 승률 S의 변화율 (R − S) / S × 100이라,
 * 분석한 N판 전체 승률 W와 함께 R − S를 거꾸로 푼다. W·N = n·R + (N − n)·S, R = S(1 + r) → S = W·N / (N + n·r).
 * 나머지 판 승률이 0%면 백엔드가 변화율을 0으로 보내 0%p로 나온다. 풀 수 없는 값이면 null.
 */
export function winRateDeltaPp(winRate: number, matches: number, recent: number, deltaPct: number): number | null {
  if (!(recent > 0 && matches > recent)) return null
  const r = deltaPct / 100
  const denom = matches + recent * r
  if (!(denom > 0)) return null
  return ((winRate * matches) / denom) * r * 100
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
    code: 'ΔPlonk', label: { ko: '순간화력', en: 'Plonk' }, kind: 'delta', decimals: 2,
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

/*
 * AI 문장에 섞여 나온 내부 키 이름 → 화면 이름. LLM이 고쳐야 할 문제지만(QA BUG-04) 화면에 delta_opener 같은
 * 코드명이 그대로 보이지 않게 프론트에서도 한 번 거른다.
 * 밑줄 있는 키(delta_opener, inf_ds …)는 어느 언어에서나 바꾸고, 밑줄 없는 낱말(opener, comeback …)은
 * 영어 문장에서는 평범한 단어일 수 있어 한국어 문장에서만 바꾼다.
 */
const RAW_NAMES: Record<string, string> = {
  ...Object.fromEntries(Object.entries(STAT_META).map(([k, m]) => [k, m.code])),
  opener: 'Opener',
  plonk: 'Plonk',
  stride: 'Stride',
  inf_ds: 'Inf DS',
  app: 'APP',
  weighted_app: 'Weighted APP',
  vs_apm: 'VS/APM',
  cheese_index: 'Cheese Index',
  comeback: 'Comeback',
  vs_slope: 'VS Slope',
}

/*
 * 헤비 챕터 id · 응답 필드 이름 — 지표 코드가 없는 말이라 언어별 화면 이름으로 (QA 2차 BUG-04 남은 1건).
 * strength_split · comeback_rate · session_vs_slope 챕터는 지표 키와 같아 RAW_NAMES가 먼저 바꾼다.
 */
const FIELD_NAMES: Record<Lang, Record<string, string>> = {
  ko: {
    tr_trend: 'TR 추이',
    playstyle: '플레이스타일',
    playstyle_relative: '플레이스타일',
    attack: '공격',
    defense: '수비',
    rivals: '자주 만난 상대',
    round_curves: '라운드별 곡선',
    win_rate: '승률',
    recent_form: '최근 승패',
    match_count: '경기 수',
    fixed_metrics: '고정 지표',
    delta_metrics: '상대 비교 지표',
    strength_quintiles: '상대 강도 5구간',
    comeback_samples: '역전 표본',
  },
  en: {
    tr_trend: 'TR trend',
    playstyle_relative: 'playstyle',
    round_curves: 'round curves',
    win_rate: 'win rate',
    recent_form: 'recent form',
    match_count: 'games',
    fixed_metrics: 'fixed metrics',
    delta_metrics: 'relative metrics',
    strength_quintiles: 'opponent-strength quintiles',
    comeback_samples: 'comeback samples',
  },
}

/**
 * 언어는 화면 언어가 아니라 낱말 바로 앞뒤로 정한다 — 한국어 화면에도 LLM이 영어로 쓰는 일이 있어,
 * 화면 언어를 따르면 영어 문장의 attack · defense 같은 평범한 낱말이 '공격' · '수비'로 바뀌어 섞인다.
 * 'plonk와'처럼 한글이 붙어 있거나 이웃해 있으면 한국어 문장 속 낱말로 본다.
 */
export function readable(text: string): string {
  /* LLM이 원값을 그대로 옮긴 긴 소수(−0.0106634 · 0.541456)는 화면 자릿수로 줄인다 — 1 이상은 둘째, 미만은 셋째 자리 */
  text = text.replace(/\d+\.\d{4,}/g, (m) => {
    const v = Number(m)
    return v.toFixed(v >= 1 ? 2 : 3)
  })
  return text.replace(/\b[a-z]+(?:_[a-z]+)*\b/g, (w, at: number) => {
    const near = text.slice(Math.max(0, at - 2), at) + text.slice(at + w.length, at + w.length + 2)
    const lang: Lang = /[가-힣]/.test(near) ? 'ko' : 'en'
    const name = RAW_NAMES[w] ?? FIELD_NAMES[lang][w]
    if (!name) return w
    return w.includes('_') || lang === 'ko' ? name : w
  })
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

/** 수집했지만 분석에서 뺀 경기 — 정제 제외 + 구조 이상으로 버린 기록. 백엔드가 안 주면(콜드스타트 등) null */
export function skippedOf(data: StatsResponse): number | null {
  const meta = data.chapters
  if (data.cold_start || typeof meta?.excluded_matches !== 'number') return null
  return meta.excluded_matches + (typeof meta.dropped_records === 'number' ? meta.dropped_records : 0)
}

/** 하이라이트 카드 순서별 색 — 카드 테두리와 AI READOUT 문장 속 구절 밑줄이 같은 색 */
export const HIGHLIGHT_COLORS = ['var(--color-piece-s)', 'var(--color-piece-l)', 'var(--color-piece-t)']

/** 하이라이트 카드의 DOM id — 총평 구절을 누르면 그 카드로 스크롤 */
export const highlightId = (stat: string) => `hl-${stat}`

/*
 * 총평 문장에서 하이라이트 지표를 가리키는 말 — 화면 이름·코드 말고도 LLM이 흔히 쓰는 표현.
 * 긴 말부터 맞춰 보므로 '가비지 처리 중심'이 '가비지 처리'보다 먼저 잡힌다.
 */
const CUES: Record<StatKey, { ko: string[]; en: string[] }> = {
  tr_trend_delta: { ko: ['TR 흐름'], en: ['TR trend'] },
  delta_opener: { ko: ['초반 전개', '초반', '오프너'], en: ['opener', 'early game'] },
  delta_plonk: { ko: ['순간화력', '순간 화력', '플롱크'], en: ['plonk', 'burst'] },
  delta_stride: { ko: ['지속 전개', '스트라이드', '속도'], en: ['stride', 'speed'] },
  delta_inf_ds: { ko: ['가비지 처리 중심', '다운스택'], en: ['inf ds', 'downstack'] },
  delta_app: { ko: ['블록당 공격', '공격 효율', '공격량', '공격'], en: ['attack per piece', 'attack efficiency', 'attack'] },
  delta_weighted_app: { ko: ['공격 성향', '공격적'], en: ['attack tendency', 'aggressive'] },
  delta_vs_apm: { ko: ['공격 대비 방어', '방어 비율', '수비', '방어'], en: ['defense-to-attack', 'defense', 'defensive'] },
  delta_cheese_index: { ko: ['가비지 처리', '치즈', '가비지'], en: ['cheese', 'garbage'] },
  strength_split: { ko: ['상대 강도', '강한 상대', '약한 상대'], en: ['opponent strength', 'stronger opponents', 'weaker opponents'] },
  comeback_rate: { ko: ['역전승'], en: ['comeback rate'] },
  comeback_rate_against: { ko: ['역전패', '역전 허용'], en: ['blown lead'] },
  delta_comeback: { ko: ['역전', '뒤집'], en: ['comeback', 'turn around', 'turned around'] },
  session_vs_slope: { ko: ['경기 내 컨디션', '컨디션', '라운드'], en: ['condition', 'later rounds', 'round'] },
}

export interface SummaryPart {
  text: string
  /** 이 구절이 가리키는 하이라이트 지표 — 평문이면 없다 */
  stat?: StatKey
}

/* 구절 끝 — 한국어는 이어지거나 끝나는 어미(…고 / …며 / …지만 / …다), 둘 다 문장부호에서 끊는다 */
/* '…보다'(조사), '…에서'(조사)는 구절 끝이 아니다 */
const CLAUSE_END: Record<Lang, RegExp> = { ko: /(고|며|지만|는데|면서|(?<!에)서|(?<!보)다|요)$/, en: /^$/ }
const PUNCT = /[,.!?;:·]$/

/** 이 어절에서 구절이 끝나는지 — '…하고 있으며'처럼 뒤에 있다·없다가 붙으면 아직 안 끝났다 */
function endsClause(word: string, next: string | undefined, lang: Lang): boolean {
  if (PUNCT.test(word)) return true
  if (lang === 'ko' && word.endsWith('고') && next && /^(있|없|싶)/.test(next)) return false
  return CLAUSE_END[lang].test(word)
}

/* 카드 문장과 겹치는 말을 셀 때 — 끝에 붙은 조사는 떼고, 어디에나 나오는 말은 세지 않는다 */
const PARTICLES = ['에게서', '에서는', '으로는', '에게', '보다', '으로', '에서', '처럼', '까지', '부터', '이며', '이고', '하며', '하고', '은', '는', '이', '가', '을', '를', '의', '에', '로', '와', '과', '도', '만']
const STOP = new Set(['상대', '경기', '경향', '플레이', '있다', '있으며', '있습니다', '합니다', '입니다', '하는', '것이', '것은', 'the', 'and', 'is', 'are', 'with', 'than', 'your', 'you', 'their', 'more', 'less', 'opponents', 'opponent'])

function norm(word: string): string {
  let w = word.toLowerCase().replace(/[.,!?;:·()"'“”‘’]/g, '')
  for (const p of PARTICLES) {
    if (w.length - p.length >= 2 && w.endsWith(p)) {
      w = w.slice(0, -p.length)
      break
    }
  }
  return w.length >= 2 && !STOP.has(w) ? w : ''
}

const sameWord = (a: string, b: string) => a === b || a.startsWith(b) || b.startsWith(a)

/*
 * 겹쳐도 근거가 약한 말 — 총평 첫머리("플레이스타일과 강도에 …", "당신은 …")에 늘 나와서, 이것끼리만 겹치면 엉뚱한 카드로 이어진다.
 * 겹친 말 가운데 이 목록 밖의 말이 하나는 있어야 잇는다 (실제 유저 67명 총평 검토, 2026-10-12).
 */
const WEAK = new Set(['플레이스타일', '스타일', '상대적', '상대적인', '강도', '가장', '당신', '성향', '주요', '요인', '영향', '결과', '분석', '데이터', '전적', '나타났습니다', '보입니다', '약간'])

/**
 * 총평을 구절로 나눈다. 두 단계:
 * 1. 하이라이트 지표마다 지표를 가리키는 표현(CUES · 이름 · 코드)이 처음 나오는 곳에서, 그 어절부터 구절이 끝나는 어절까지(최대 4어절).
 * 2. 1에서 못 찾은 지표는 그 카드의 AI 문장과 낱말이 2개 이상 겹치는 구절 — 총평과 카드 문장을 같은 LLM이 써서
 *    "상대보다 우위를 보이고 있으며" ↔ "상대보다 우위를 보는 플레이스타일"처럼 같은 말을 되풀이한다.
 *    겹치는 첫 어절부터 그 구절 끝까지.
 * 어느 쪽으로도 못 찾은 지표는 구절이 없다. 순수 함수라 화면과 따로 시험한다.
 */
export function linkSummary(text: string, stats: StatKey[], lang: Lang, sentences: Partial<Record<StatKey, string>> = {}): SummaryPart[] {
  const lower = text.toLowerCase()
  const cand = stats.flatMap((stat) => [...CUES[stat][lang], STAT_META[stat].label[lang], STAT_META[stat].code.replace(/^Δ/, '')].map((w) => ({ stat, w: w.toLowerCase() })))
  cand.sort((a, b) => b.w.length - a.w.length)

  const hits: { start: number; end: number; stat: StatKey }[] = []
  const free = (s: number, e: number) => hits.every((h) => e <= h.start || s >= h.end)
  for (const { stat, w } of cand) {
    if (hits.some((h) => h.stat === stat)) continue
    for (let from = 0; ; ) {
      const i = lower.indexOf(w, from)
      if (i < 0) break
      /* 어절 경계로 넓힌다 — 앞은 공백까지, 뒤는 구절 끝 어절까지 */
      const start = text.lastIndexOf(' ', i) + 1
      let end = i + w.length
      for (let words = 0; words < 4; words++) {
        const sp = text.indexOf(' ', text[end] === ' ' ? end + 1 : end)
        end = sp < 0 ? text.length : sp
        const word = text.slice(start, end)
        if (PUNCT.test(word)) {
          end -= 1
          break
        }
        if (sp < 0 || endsClause(word, text.slice(end + 1).split(' ')[0], lang)) break
      }
      if (free(start, end)) {
        hits.push({ start, end, stat })
        break
      }
      from = i + w.length
    }
  }

  /* 2단계 — 어절을 구절로 묶고, 카드 문장과 겹치는 낱말이 가장 많은 구절 */
  const words = [...text.matchAll(/\S+/g)].map((m) => ({ s: m.index, e: m.index + m[0].length, w: m[0], n: norm(m[0]) }))
  const clauses: (typeof words)[] = []
  let cur: typeof words = []
  for (const [k, w] of words.entries()) {
    cur.push(w)
    if (endsClause(w.w, words[k + 1]?.w, lang)) {
      clauses.push(cur)
      cur = []
    }
  }
  if (cur.length) clauses.push(cur)

  for (const stat of stats) {
    const sentence = sentences[stat]
    if (!sentence || hits.some((h) => h.stat === stat)) continue
    const want = [...new Set(sentence.split(/\s+/).map(norm).filter(Boolean))]
    let best: { start: number; end: number; score: number } | null = null
    for (const c of clauses) {
      const start0 = c[0].s
      const last = c[c.length - 1]
      const end = PUNCT.test(last.w) ? last.e - 1 : last.e
      if (!free(start0, end)) continue
      const matched = want.filter((t) => c.some((w) => w.n && sameWord(w.n, t)))
      if (matched.length < 2 || matched.every((t) => WEAK.has(t)) || (best && matched.length <= best.score)) continue
      const first = c.find((w) => w.n && matched.some((t) => sameWord(w.n, t)))!
      best = { start: first.s, end, score: matched.length }
    }
    if (best) hits.push({ start: best.start, end: best.end, stat })
  }

  hits.sort((a, b) => a.start - b.start)
  const parts: SummaryPart[] = []
  let at = 0
  for (const h of hits) {
    if (h.start > at) parts.push({ text: text.slice(at, h.start) })
    parts.push({ text: text.slice(h.start, h.end), stat: h.stat })
    at = h.end
  }
  if (at < text.length) parts.push({ text: text.slice(at) })
  return parts
}

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
