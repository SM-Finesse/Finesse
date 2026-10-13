/*
 * 랭크 글자 색 — TETR.IO 랭크 색을 여기에 그대로 적어 둔다(API로 받아 오지 않는다).
 * 색을 바꾸려면 이 표만 고치면 된다.
 */
export const RANK_COLORS: Record<string, string> = {
  'x+': '#A763EA',
  x: '#FF45FF',
  u: '#FF3813',
  ss: '#DB8B1F',
  's+': '#D8AF0E',
  s: '#E0A71B',
  's-': '#B2972B',
  'a+': '#1FA834',
  a: '#46AD51',
  'a-': '#3BB687',
  'b+': '#4F99C0',
  b: '#4F64C9',
  'b-': '#5650C7',
  'c+': '#552883',
  c: '#733E8F',
  'c-': '#79558C',
  'd+': '#8E6091',
  d: '#907591',
  z: '#828282',
}

/** 모르는 값이면 undefined — 부르는 쪽 기본 글자색을 그대로 쓴다 */
export const rankColor = (rank: string | undefined): string | undefined => (rank ? RANK_COLORS[rank.toLowerCase()] : undefined)
