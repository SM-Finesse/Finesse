/* 닉네임에서 만든 블록 아바타 — 좌우 대칭 5×5 격자. 같은 이름이면 언제나 같은 모양 */
const COLORS = ['#4FC3D9', '#D3BE55', '#B76AD0', '#8FC93A', '#D9524C', '#5A87CE', '#34B79C']

function hash(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

export function Avatar({ name, size }: { name: string; size: number }) {
  const h = hash(name)
  const c1 = COLORS[h % 7]
  const i2 = (h >>> 5) % 7
  const c2 = COLORS[i2] === c1 ? COLORS[(i2 + 1) % 7] : COLORS[i2]
  const n = 5
  const cell = size / n

  const blocks = []
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < 3; x++) {
      if (!((h >>> (y * 3 + x)) & 1)) continue
      const fill = (h >>> (y + x + 7)) & 1 ? c1 : c2
      for (const cx of x === 2 ? [2] : [x, n - 1 - x]) {
        const px = cx * cell
        const py = y * cell
        blocks.push(
          <g key={`${cx}-${y}`}>
            <rect x={px} y={py} width={cell} height={cell} fill={fill} />
            <rect x={px} y={py} width={cell} height={cell * 0.16} fill="#fff" opacity={0.28} />
            <rect x={px} y={py + cell * 0.84} width={cell} height={cell * 0.16} fill="#000" opacity={0.26} />
          </g>,
        )
      }
    }
  }

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true" className="block rounded-[5px]">
      <rect width={size} height={size} fill="#0C1824" />
      {blocks}
    </svg>
  )
}
