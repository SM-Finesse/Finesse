import type { RivalItem } from '../api/types'

/*
 * 자주 만난 상대 — 같은 상대에게 반복해서 이기거나 지는 패턴 자체가 정보다(FR-08).
 * 5경기 미만은 우연과 구분되지 않아 태그를 달지 않는다.
 */
export const REPEAT_GAMES = 5

export type RivalTag = 'bad' | 'good' | 'even' | null

export const winRateOf = (r: RivalItem) => (r.matches > 0 ? (r.wins / r.matches) * 100 : 0)

export function rivalTag(r: RivalItem): RivalTag {
  if (r.matches < REPEAT_GAMES) return null
  const wr = winRateOf(r)
  if (wr <= 40) return 'bad'
  if (wr >= 65) return 'good'
  if (wr >= 45 && wr <= 55) return 'even'
  return null
}

/** 최근 대전 시각(ms). 없거나 읽을 수 없으면 가장 오래된 것으로 본다 */
const lastAt = (r: RivalItem) => {
  const t = r.last_match_at ? Date.parse(r.last_match_at) : NaN
  return Number.isNaN(t) ? -Infinity : t
}

/** 조우 횟수 내림차순, 같으면 최근에 대전한 상대가 위(FR-08). 그래도 같으면 받은 순서 그대로 */
export const sortRivals = (items: RivalItem[]) =>
  items
    .map((r, i) => ({ r, i }))
    .sort((a, b) => b.r.matches - a.r.matches || lastAt(b.r) - lastAt(a.r) || a.i - b.i)
    .map((x) => x.r)

/** 타일 값 — 표의 태그와 같은 기준. 기준 미달이면 null(이름을 만들어내지 않는다) */
export function rivalSummary(items: RivalItem[]) {
  const repeat = items.filter((r) => r.matches >= REPEAT_GAMES)
  let worst: RivalItem | null = null
  let best: RivalItem | null = null
  for (const r of repeat) {
    const wr = winRateOf(r)
    if (wr <= 40 && (!worst || wr < winRateOf(worst))) worst = r
    if (wr >= 65 && (!best || wr > winRateOf(best))) best = r
  }
  return { repeat, worst, best }
}

/** 페이지 버튼 — 처음 · 끝 · 지금 ±1, 사이가 비면 … */
export function pageList(page: number, total: number): (number | '…')[] {
  const out: (number | '…')[] = []
  let last = 0
  for (let p = 1; p <= total; p++) {
    if (p !== 1 && p !== total && Math.abs(p - page) > 1) continue
    if (last && p - last > 1) out.push('…')
    out.push(p)
    last = p
  }
  return out
}
