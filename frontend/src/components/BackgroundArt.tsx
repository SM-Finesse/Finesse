import type { ReactElement } from 'react'

/* 배경 — 네 칸짜리 조각들이 바닥에 쌓이고, 좌우 여백에 몇 개가 떠 있다 */
type Kind = 'I' | 'O' | 'T' | 'S' | 'Z' | 'J' | 'L'
const PIECES: Record<Kind, ReadonlyArray<readonly [number, number]>> = {
  I: [[0, 0], [1, 0], [2, 0], [3, 0]],
  O: [[0, 0], [1, 0], [0, 1], [1, 1]],
  T: [[0, 0], [1, 0], [2, 0], [1, 1]],
  S: [[1, 0], [2, 0], [0, 1], [1, 1]],
  Z: [[0, 0], [1, 0], [1, 1], [2, 1]],
  J: [[0, 0], [0, 1], [1, 1], [2, 1]],
  L: [[2, 0], [0, 1], [1, 1], [2, 1]],
}
const PC: Record<Kind, string> = {
  I: '#4FC3D9', O: '#D3BE55', T: '#B76AD0', S: '#8FC93A', Z: '#D9524C', J: '#5A87CE', L: '#34B79C',
}
type Placed = readonly [Kind, number, number, number]

/* 타일은 background-repeat로 이어 붙인다. 경계를 넘는 조각은 반대편에도 한 벌 더 그려
   타일이 맞물릴 때 잘린 쪽과 이어지게 한다. */
function piece(kind: Kind, gx: number, gy: number, cell: number, op: number, rot: number, W: number, H: number): string {
  const cells = PIECES[kind]
  const col = PC[kind]
  let out = ''
  for (const [c, r] of cells) {
    out +=
      `<rect x="${(gx + c) * cell}" y="${(gy + r) * cell}" width="${cell - 2}" height="${cell - 2}" rx="2" ` +
      `fill="${col}" fill-opacity="${(op * 0.4).toFixed(3)}" stroke="${col}" stroke-opacity="${(op * 1.6).toFixed(3)}" stroke-width="1.5"/>`
  }
  if (rot) out = `<g transform="rotate(${rot} ${(gx + 1.5) * cell} ${(gy + 1) * cell})">${out}</g>`
  const maxC = Math.max(...cells.map((p) => p[0]))
  const maxR = Math.max(...cells.map((p) => p[1]))
  const pad = cell * 1.3
  const dxs = [0]
  const dys = [0]
  if (gx * cell - pad < 0) dxs.push(W)
  if ((gx + maxC + 1) * cell + pad > W) dxs.push(-W)
  if (gy * cell - pad < 0) dys.push(H)
  if ((gy + maxR + 1) * cell + pad > H) dys.push(-H)
  let all = ''
  for (const dx of dxs) for (const dy of dys) all += dx || dy ? `<g transform="translate(${dx},${dy})">${out}</g>` : out
  return all
}

function tile(list: readonly Placed[], cell: number, op: number, W: number, H: number): string {
  const body = list.map(([k, x, y, r]) => piece(k, x, y, cell, op, r, W, H)).join('')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${body}</svg>`
  return `url("data:image/svg+xml;utf8,${encodeURIComponent(svg).replace(/'/g, '%27')}")`
}

const AIR: readonly Placed[] = [
  ['T', 0, 1, -12], ['O', 1, 4, 7], ['S', 1, 8, 14], ['J', 0, 11, -5], ['L', 0, 15, -6],
  ['I', 0, 18, 9], ['Z', 1, 21, 10], ['T', 1, 25, -8], ['O', 0, 28, 5],
  ['O', 45, 2, 6], ['J', 44, 5, -11], ['T', 45, 9, 8], ['Z', 44, 12, 7], ['I', 43, 16, 10],
  ['S', 45, 20, -13], ['L', 44, 23, 6], ['O', 45, 27, -7], ['T', 43, 30, 11],
]
const FAR: readonly Placed[] = [
  ['L', 0, 1, -9], ['I', 22, 4, 6], ['T', 1, 9, 12], ['Z', 23, 13, -7], ['J', 0, 17, 8], ['O', 24, 21, -11], ['S', 22, 8, 5],
]
const AIR_URL = tile(AIR, 30, 0.24, 1470, 1020)
const FAR_URL = tile(FAR, 58, 0.085, 1470, 1240)

export function SkyArt() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
      <div className="absolute inset-0 bg-repeat bg-top" style={{ backgroundImage: FAR_URL, backgroundSize: '1470px 1240px' }} />
      <div className="absolute inset-0 bg-repeat bg-top" style={{ backgroundImage: AIR_URL, backgroundSize: '1470px 1020px' }} />
    </div>
  )
}

/* 바닥 — 화면 아래쪽에 꽉 쌓인 블록 더미. 윗줄은 들쭉날쭉하게. 시드 고정이라 매번 같은 모양 */
const FLOOR_COLORS = ['#3FD0F0', '#F2CB4A', '#C86FE6', '#6ED845', '#F0524A', '#4A86F0', '#29C4AA']
const CELL = 26
const COLS = 58
const ROWS = 8

function buildFloor(): ReactElement[] {
  let seed = 20260908
  const rnd = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  }
  const H = ROWS * CELL
  const s = CELL
  const out: ReactElement[] = []
  let h = 4
  for (let c = 0; c < COLS; c++) {
    h += Math.round((rnd() - 0.46) * 4.6)
    h = Math.min(ROWS, Math.max(2, h))
    for (let r = 0; r < h; r++) {
      const x = c * s
      const y = H - (r + 1) * s
      out.push(
        <g key={`${c}-${r}`}>
          <rect x={x} y={y} width={s} height={s} fill={FLOOR_COLORS[Math.floor(rnd() * 7)]} />
          <rect x={x + s * 0.24} y={y + s * 0.24} width={s * 0.52} height={s * 0.52} fill="none" stroke="#FFFFFF" strokeOpacity=".40" strokeWidth={(s * 0.11).toFixed(2)} />
          <rect x={x} y={y} width={s} height={s * 0.13} fill="#FFFFFF" opacity=".26" />
          <rect x={x} y={y + s * 0.87} width={s} height={s * 0.13} fill="#000000" opacity=".26" />
          <rect x={x + 0.5} y={y + 0.5} width={s - 1} height={s - 1} fill="none" stroke="#0A1620" strokeOpacity=".55" />
        </g>,
      )
    }
  }
  return out
}
const FLOOR = buildFloor()

export function FloorArt() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 bottom-0 h-[230px] opacity-65">
      <svg viewBox={`0 0 ${COLS * CELL} ${ROWS * CELL}`} preserveAspectRatio="xMidYMax slice" width="100%" height="100%">
        {FLOOR}
      </svg>
    </div>
  )
}
