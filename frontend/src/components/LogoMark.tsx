/* 5×5 격자에 블록으로 쌓은 F, 맨 아랫줄은 채워진 한 줄.
   테트로미노(4칸 조각) 형태는 상표 문제로 쓰지 않고, 격자와 완성된 줄로만 표현한다. */
const C = 6
const F_CELLS: ReadonlyArray<readonly [number, number]> = [[0, 0], [1, 0], [2, 0], [0, 1], [0, 2], [1, 2], [0, 3]]
const GRID = [1, 2, 3, 4]
const BOTTOM = [0, 1, 2, 3, 4]

function Cell({ c, r, fill }: { c: number; r: number; fill: string }) {
  const x = 1 + c * C
  const y = 1 + r * C
  return (
    <g>
      <rect x={x} y={y} width="5" height="5" rx="1" fill={fill} />
      <rect x={x} y={y} width="5" height="1.3" fill="#fff" opacity=".30" />
      <rect x={x} y={y + 3.7} width="5" height="1.3" fill="#000" opacity=".30" />
    </g>
  )
}

export function LogoMark({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 32 32" aria-hidden="true">
      <rect x="0" y="0" width="32" height="32" rx="3" fill="#0E1922" />
      {GRID.map((g) => (
        <g key={g} stroke="#1C3244">
          <line x1={1 + g * C - 0.5} y1="1" x2={1 + g * C - 0.5} y2="31" />
          <line x1="1" y1={1 + g * C - 0.5} x2="31" y2={1 + g * C - 0.5} />
        </g>
      ))}
      {F_CELLS.map(([c, r]) => (
        <Cell key={`${c}-${r}`} c={c} r={r} fill="#66C0F4" />
      ))}
      {BOTTOM.map((c) => (
        <Cell key={`b${c}`} c={c} r={4} fill="#A0F2DF" />
      ))}
      <rect x="1" y={1 + 4 * C - 0.4} width="30" height="1.1" fill="#FFFFFF" opacity=".55" />
      <rect x=".6" y=".6" width="30.8" height="30.8" rx="3" fill="none" stroke="#2A475E" />
    </svg>
  )
}
