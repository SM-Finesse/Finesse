/** Recharts 축 글자 — 차트마다 같은 모양 */
export const AXIS_TICK = { fill: '#6E7D88', fontSize: 11, fontFamily: 'Oxanium, sans-serif', fontWeight: 700 }

/** raw 이상인 가장 작은 1·2·5 × 10ⁿ */
function niceStep(raw: number): number {
  const p = 10 ** Math.floor(Math.log10(raw))
  return [1, 2, 5, 10].map((f) => f * p).find((x) => x >= raw) ?? 10 * p
}

/**
 * Y축 범위와 눈금 — 값 범위를 1·2·5 단위 눈금 4칸 안팎으로 감싼다(최소 간격 1).
 * Recharts에 범위만 주면 그릴 때마다 눈금을 골라 끝 눈금이 빠질 수 있어 눈금까지 정해서 넘긴다.
 */
export function niceScale(lo: number, hi: number, count = 4): { domain: [number, number]; ticks: number[] } {
  const step = Math.max(1, niceStep((hi - lo) / count || 1))
  let a = Math.floor(lo / step) * step
  let b = Math.ceil(hi / step) * step
  if (a === b) {
    a -= step
    b += step
  }
  const ticks: number[] = []
  for (let v = a; v <= b + step / 2; v += step) ticks.push(v)
  return { domain: [a, b], ticks }
}

/**
 * X축(경기 번호 1~n) 눈금 — 1과 n, 그 사이 1·2·5 단위 배수. 양 끝과 반 칸 안으로 붙는 배수는 뺀다.
 * 화면 폭·폰트 로딩에 따라 Recharts가 매번 다르게 고르지 않도록 고정해서 넘긴다.
 */
export function gameTicks(n: number, count = 5): number[] {
  if (n <= 1) return [1]
  const step = Math.max(1, niceStep((n - 1) / count))
  const mid: number[] = []
  for (let v = step; v < n; v += step) if (v - 1 > step / 2 && n - v > step / 2) mid.push(v)
  return [1, ...mid, n]
}

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
