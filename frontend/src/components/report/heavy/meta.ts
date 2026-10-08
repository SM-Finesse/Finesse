import type { HeavyChapterId } from '../../../api/types'

/* 챕터 색 — 블록 7색 순서대로, 8장은 다시 처음 색 (프로토타입 CHAPC) */
export const CHAPTER_COLORS: Record<HeavyChapterId, string> = {
  tr_trend: '#4FC3D9',
  playstyle: '#D3BE55',
  attack: '#B76AD0',
  defense: '#8FC93A',
  strength_split: '#D9524C',
  comeback_rate: '#5A87CE',
  session_vs_slope: '#34B79C',
  rivals: '#4FC3D9',
}

/** 챕터 영문 제목 — 화면에서는 대문자로 쓴다 */
export const EYEBROWS: Record<HeavyChapterId, string> = {
  tr_trend: 'TR / Ability Trend',
  playstyle: 'Playstyle Relative',
  attack: 'Attack Efficiency',
  defense: 'Defense / Garbage',
  strength_split: 'Strength Split',
  comeback_rate: 'Comeback Performance',
  session_vs_slope: 'In-Game Condition',
  rivals: 'Rivals',
}
