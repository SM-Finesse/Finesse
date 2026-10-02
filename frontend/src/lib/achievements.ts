import type { FeaturedAchievement } from '../api/types'

/*
 * TETR.IO 업적 메달 — ch.tetr.io의 CreateAchievement를 옮긴 것.
 * 메달 = 등급 테두리(frames) + 경쟁 업적 Top 100 화환(wreaths) + 아이콘(8×8 스프라이트 한 칸).
 */

const RES = 'https://tetr.io/res/achievements'

export type AchievementRank = 'none' | 'bronze' | 'silver' | 'gold' | 'platinum' | 'diamond' | 'issued'

const RANKS: Record<number, AchievementRank> = { 0: 'none', 1: 'bronze', 2: 'silver', 3: 'gold', 4: 'platinum', 5: 'diamond', 100: 'issued' }

/** art 값 — 2면 리더보드 Top 100 안에 들 때 화환이 붙는다 */
const COMPETITIVE = 2
const WREATHS: [number, string][] = [
  [3, 't3'],
  [5, 't5'],
  [10, 't10'],
  [25, 't25'],
  [50, 't50'],
  [100, 't100'],
]

export const achievementRank = (rank: number): AchievementRank => RANKS[rank] ?? 'none'

export const frameUrl = (rank: number) => `${RES}/frames/${achievementRank(rank)}.png`

/** 경쟁 업적이고 Top 100 안이면 순위 구간 화환, 아니면 null */
export function wreathUrl(a: Pick<FeaturedAchievement, 'art' | 'pos'>): string | null {
  if (a.art !== COMPETITIVE || a.pos < 0) return null
  const tier = WREATHS.find(([n]) => a.pos < n)
  return tier ? `${RES}/wreaths/${tier[1]}.png` : null
}

/**
 * 아이콘 — 시트 한 장에 64개(8×8). 세로 위치가 시트 높이를 넘어가도 배경이 반복돼 같은 칸을 가리킨다.
 * 배경 크기를 800%로 두고 칸 위치를 %로 옮긴다.
 */
export function iconStyle(k: number): { backgroundImage: string; backgroundPosition: string; backgroundSize: string } {
  const i = k - 1
  return {
    backgroundImage: `url('${RES}/icons/${Math.floor(i / 64)}.png')`,
    backgroundPosition: `${(i % 8) * -100}% ${Math.floor(i / 8) * -100}%`,
    backgroundSize: '800% 800%',
  }
}
