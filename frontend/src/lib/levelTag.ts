/*
 * TETR.IO 레벨 태그 — ch.tetr.io의 RenderLevelNumber · .leveltag 스타일을 옮긴 것.
 * 레벨에 따라 세 가지가 바뀐다.
 *   배지 색: 500레벨마다 (10색 순환)
 *   장식 모양: 100레벨마다 (5종 순환)
 *   장식 색: 10레벨마다 (10색 순환)
 * 5000레벨부터는 금색 태그 하나로 고정.
 */

export type LevelTagKind = { badge: string; badgeClip: string; item: string; itemClip: string; color: string; shadow: string }

/** [그라데이션 어두운 끝, 중간 위, 중간 아래, 아래 끝] — 위아래 반으로 나뉜 광택 */
const BADGE: [string, string, string, string][] = [
  ['#A9A9A9', '#C9C9C9', '#E4E4E4', '#C3C3C3'],
  ['#BC3535', '#FD3535', '#FF6D6D', '#F02D2D'],
  ['#BB6A34', '#F56200', '#FFA162', '#EF7320'],
  ['#C6B734', '#E9D41E', '#EDDE5F', '#E9D41E'],
  ['#82B933', '#90DD21', '#B5F856', '#90DD21'],
  ['#31B951', '#23EE53', '#7DF89A', '#23EE53'],
  ['#31A89B', '#22F0DA', '#8AFDF1', '#22F0DA'],
  ['#31599B', '#1F6CEC', '#84B2FE', '#1F6CEC'],
  ['#673ABA', '#8644FF', '#BB96FF', '#8644FF'],
  ['#AA35AB', '#E81BEA', '#FEA4FF', '#E81BEA'],
]
/** 글자색 — 밝은 배지는 검정, 어두운 배지는 흰색 */
const DARK_TEXT = [true, false, true, true, true, true, true, false, false, false]

/** [기본, 가운데 하이라이트] */
const ITEM: [string, string][] = [
  ['#C9C9C9', '#E4E4E4'],
  ['#FD3535', '#FF6D6D'],
  ['#F56200', '#FFA162'],
  ['#E9D41E', '#EDDE5F'],
  ['#90DD21', '#B5F856'],
  ['#23EE53', '#7DF89A'],
  ['#22F0DA', '#8AFDF1'],
  ['#1F6CEC', '#84B2FE'],
  ['#8644FF', '#BB96FF'],
  ['#E81BEA', '#FEA4FF'],
]

/** 왼쪽 모서리를 깎은 배지 몸통 — 오른쪽 끝 모양만 장식에 맞춰 바뀐다 */
const LEFT = '0.2em 100%, 0 calc(100% - 0.2em), 0 0.2em'
const BADGE_CLIP = [
  `polygon(0.2em 0, 100% 0, calc(100% - 0.7em) 100%, ${LEFT})`,
  `polygon(0.2em 0, 100% 0, calc(100% - 0.7em) 100%, ${LEFT})`,
  `polygon(0.2em 0, 100% 0, calc(100% - 0.6em) 50%, 100% 100%, ${LEFT})`,
  `polygon(0.2em 0, 100% 0, calc(100% - 0.5em) 50%, 100% 100%, ${LEFT})`,
  `polygon(0.2em 0, 100% 0, calc(100% - 0.4em) 30%, calc(100% - 0.4em) 70%, 100% 100%, ${LEFT})`,
]
/** 배지 오른쪽에 붙는 장식 — 사선 · 삼각형 · 마름모 · 오각형 · 육각형 */
const ITEM_CLIP = [
  'polygon(0.7em 0, 1em 0, 0.3em 100%, 0 100%)',
  'polygon(0.7em 0, 1.4em 100%, 0 100%)',
  'polygon(0.7em 0, 0.1em 50%, 0.7em 100%, 1.3em 50%)',
  'polygon(0.7em 0, 0.2em 50%, 0.7em 100%, 1.2em 75%, 1.2em 25%)',
  'polygon(0.75em 0, 0.25em 30%, 0.25em 70%, 0.75em 100%, 1.25em 70%, 1.25em 30%)',
]

const SHADOW = '0 0 2px #0006'

const GOLDEN: LevelTagKind = {
  badge: 'linear-gradient(to bottom, #FFD800 0%, #FFF 50%, #FF7800 50%, #FFD800 100%)',
  badgeClip: `polygon(0.2em 0, calc(100% - 0.7em) 0, calc(100% - 0.35em) 50%, calc(100% - 0.7em) 100%, ${LEFT})`,
  item: 'linear-gradient(to bottom, #88BAD9 0%, #FFF 50%, #776DDC 50%, #BBB5F0 100%)',
  itemClip: 'polygon(0 0, 0.3em 0, 0.65em 50%, 0.3em 100%, 0 100%, 0.3em 50%)',
  color: '#FFF',
  shadow: '0 0 4px #FF5400, 0 0 4px #FF5400, 0 0 4px #FF5400, 0 0 2px #FF5400, 0 0 2px #FF5400, 0 0 2px #FF5400',
}

export function levelTagKind(level: number): LevelTagKind {
  if (level >= 5000) return GOLDEN
  const shape = Math.floor(level / 100) % 5
  const [b0, b1, b2, b3] = BADGE[Math.floor(level / 500) % 10]
  const [i0, i1] = ITEM[Math.floor(level / 10) % 10]
  return {
    badge: `linear-gradient(to bottom, ${b0} 0%, ${b1} 50%, ${b2} 50%, ${b3} 100%)`,
    badgeClip: BADGE_CLIP[shape],
    item: `linear-gradient(to bottom, ${i0} 0%, ${i1} 50%, ${i0} 100%)`,
    itemClip: ITEM_CLIP[shape],
    color: DARK_TEXT[Math.floor(level / 500) % 10] ? '#000D' : '#FFFD',
    shadow: SHADOW,
  }
}
