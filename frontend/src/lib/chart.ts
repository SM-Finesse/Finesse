/** Recharts 축 글자 — 차트마다 같은 모양 */
export const AXIS_TICK = { fill: '#6E7D88', fontSize: 11, fontFamily: 'Oxanium, sans-serif', fontWeight: 700 }

/** 증감 방향 색 — 차트 SVG 안에서 쓴다 */
export const TREND_COLOR = { up: '#8FC93A', down: '#D9524C', even: '#8F98A0' } as const

/* 승률 색 — 10%p 단위로 딱 끊어 5단계 (프로토타입 7차 5구간 색). 80%↑ 초록 · 70%↑ 연두 · 60%↑ 노랑 · 50%↑ 분홍 · 그 아래 빨강 */
const RATE_STEPS: [number, string][] = [
  [80, '#8FC93A'],
  [70, '#B4C63F'],
  [60, '#D3BE55'],
  [50, '#D28A8A'],
]

export function rateColor(v: number): string {
  return RATE_STEPS.find(([min]) => v >= min)?.[1] ?? '#D9524C'
}
