import { vi } from 'vitest'
import type { LightCommentResponse, StatsResponse } from '../api/types'

export const STATS: StatsResponse = {
  username: 'exampleplayer',
  cold_start: false,
  match_count: 120,
  updated_at: '2026-10-01T08:00:00Z',
  profile: {
    rank: 'x',
    best_rank: 'x+',
    tr: 24321.6,
    glicko: 3000,
    rd: 60,
    apm: 237.65,
    pps: 4.16,
    vs: 471.78,
    avatar_url: 'https://tetr.io/user-content/avatars/abc.jpg?rv=1',
    xp: 30503753.95,
    country: 'MY',
    joined_at: '2020-03-26T14:25:41Z',
    play_time_seconds: 6496543.2,
    badges: [
      { id: 'secretgrade', label: 'Achieved the full Secret Grade', ts: '2020-12-27T03:59:00.900Z' },
      { id: 'snowman_2', label: 'Bottled Snowman', group: 'snowman' },
      { id: 'snowman_3', label: 'Snowman', group: 'snowman' },
    ],
    friend_count: 2438,
  },
  fixed_metrics: {
    win_rate: 0.625,
    tr_trend: [24000, 24100, 24050, 24321.6],
    recent_form: ['W', 'L', 'W', 'W', 'L', 'W', 'W', 'W', 'L', 'W'],
  },
  delta_metrics: {
    tr_trend_delta: 12.4,
    playstyle_relative: {},
    attack: { delta_app: 0.092, delta_weighted_app: 0.045 },
    defense: { delta_vs_apm: -0.055, delta_cheese_index: -16.2 },
    comeback_rate: 0.556,
    delta_comeback: 0.306,
  },
  round_curves: { pps: [], vs: [] },
  rivals: { items: [], page: 1, page_size: 20, total: 0 },
}

export const COLD: StatsResponse = {
  ...STATS,
  cold_start: true,
  match_count: 7,
  fixed_metrics: { win_rate: 3 / 7, tr_trend: [], recent_form: ['W', 'L', 'L', 'W', 'W', 'L', 'L'] },
  delta_metrics: undefined,
}

export const COMMENT: LightCommentResponse = {
  light_summary: '공격은 앞서고 수비는 밀립니다.',
  highlights: [
    { stat: 'delta_app', sentence: '같은 블록 수로 상대보다 공격을 더 만듭니다.' },
    { stat: 'delta_plonk', sentence: '계산되지 않은 지표를 가리키는 문장' },
    { stat: 'delta_vs_apm', sentence: '받은 가비지를 지우는 속도는 상대보다 느립니다.' },
  ],
}

export const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

type Handler = (url: string) => Promise<Response>

/** URL 별로 응답을 정한다. 같은 경로에 핸들러를 여러 개 주면 호출 순서대로 하나씩 쓴다(마지막 것은 반복) */
export function routeFetch(routes: { stats?: Handler[]; comment?: Handler[] }) {
  const used = { stats: 0, comment: 0 }
  const pick = (kind: 'stats' | 'comment', url: string) => {
    const list = routes[kind] ?? []
    const h = list[Math.min(used[kind]++, list.length - 1)]
    return h ? h(url) : new Promise<Response>(() => {})
  }
  const fn = vi.fn<typeof fetch>((input) => {
    const url = String(input)
    return url.includes('/comment/') ? pick('comment', url) : pick('stats', url)
  })
  vi.stubGlobal('fetch', fn)
  return { fn, urls: () => fn.mock.calls.map(([u]) => String(u)) }
}

export const pending = () => new Promise<Response>(() => {})

/* jsdom에는 EventSource가 없다 — 서버 이벤트를 테스트에서 직접 쏘는 가짜 */
export class FakeEventSource {
  static last: FakeEventSource
  static all: FakeEventSource[] = []
  readonly url: string
  closed = false
  private listeners = new Map<string, ((e: MessageEvent) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.last = this
    FakeEventSource.all.push(this)
  }
  addEventListener(type: string, fn: (e: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn])
  }
  close() {
    this.closed = true
  }
  emit(type: string, data = '') {
    for (const fn of this.listeners.get(type) ?? []) fn(new MessageEvent(type, { data }))
  }
}
