/** Recharts 축 글자 — 차트마다 같은 모양 */
export const AXIS_TICK = { fill: '#6E7D88', fontSize: 11, fontFamily: 'Oxanium, sans-serif', fontWeight: 700 }

/** 증감 방향 색 — 차트 SVG 안에서 쓴다 */
export const TREND_COLOR = { up: '#8FC93A', down: '#D9524C', even: '#8F98A0' } as const

/* 승률 색 — 낮으면 빨강, 50% 근처는 분홍·노랑, 높으면 초록. 사이 값은 섞어서 막대 높이에 따라 자연스럽게 바뀐다 */
const RATE_STOPS: [number, [number, number, number]][] = [
  [40, [0xd9, 0x52, 0x4c]],
  [50, [0xd2, 0x8a, 0x8a]],
  [60, [0xd3, 0xbe, 0x55]],
  [70, [0xb4, 0xc6, 0x3f]],
  [80, [0x8f, 0xc9, 0x3a]],
]

export function rateColor(v: number): string {
  const hex = (c: [number, number, number]) => `#${c.map((x) => Math.round(x).toString(16).padStart(2, '0')).join('')}`
  if (v <= RATE_STOPS[0][0]) return hex(RATE_STOPS[0][1])
  for (let i = 1; i < RATE_STOPS.length; i++) {
    const [v1, c1] = RATE_STOPS[i]
    if (v <= v1) {
      const [v0, c0] = RATE_STOPS[i - 1]
      const t = (v - v0) / (v1 - v0)
      return hex([0, 1, 2].map((k) => c0[k] + (c1[k] - c0[k]) * t) as [number, number, number])
    }
  }
  return hex(RATE_STOPS[RATE_STOPS.length - 1][1])
}
