import type { Badge } from '../api/types'

/** TETR.IO 배지 그림 주소 */
export const badgeUrl = (id: string) => `https://tetr.io/res/badges/${encodeURIComponent(id)}.png`

/**
 * 같은 group(없으면 id)끼리 묶는다 — ch.tetr.io의 GroupBadges와 같은 순서.
 * 묶음은 마지막으로 나온 자리 순, 묶음 안은 원래 순서.
 */
export function groupBadges(badges: Badge[]): Badge[][] {
  const groups = new Map<string, Badge[]>()
  for (const b of [...badges].reverse()) {
    const key = b.group ?? b.id
    const group = groups.get(key)
    if (group) group.push(b)
    else groups.set(key, [b])
  }
  return [...groups.values()].reverse().map((g) => g.reverse())
}
