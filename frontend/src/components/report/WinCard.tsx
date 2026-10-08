import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { formSummary, signed, trendOf } from '../../lib/stats'
import { BigNum, Caption, DeltaInline, PanelHead, PanelTag } from './parts'

const COLS = 10
const ROWS = 4

/** 승패 보드 — 칸 하나가 한 경기. 왼쪽 아래가 가장 오래된 경기, 오른쪽 위가 가장 최근 경기 */
function BlockBoard({ form, label }: { form: ('W' | 'L')[]; label: string }) {
  const cell = 30
  const gap = 5
  const bevel = 3
  const w = COLS * (cell + gap) - gap
  const h = ROWS * (cell + gap) - gap
  /* recent_form은 index 0이 최신 — 오래된 것부터 아래 줄을 채우도록 뒤집는다 */
  const oldestFirst = [...form].reverse()

  const cells = []
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const v = oldestFirst[(ROWS - 1 - r) * COLS + c]
      const x = c * (cell + gap)
      const y = r * (cell + gap)
      const fill = v === 'W' ? 'var(--color-win)' : v === 'L' ? 'var(--color-loss)' : 'var(--color-surface-2)'
      cells.push(
        <g key={`${r}-${c}`} data-result={v ?? ''}>
          <rect x={x} y={y} width={cell} height={cell} rx={1} fill={fill} opacity={v ? 1 : 0.5} />
          {v && <rect x={x} y={y} width={cell} height={bevel} fill="#fff" opacity={0.26} />}
          {v && <rect x={x} y={y + cell - bevel} width={cell} height={bevel} fill="#000" opacity={0.24} />}
        </g>,
      )
    }
  }
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h} preserveAspectRatio="xMinYMid meet" role="img" aria-label={label}>
      {cells}
    </svg>
  )
}

/** 최근 경기 창(최대 40경기)의 승패 — 프로필의 전체 승률과 견줘 지금 흐름을 보여준다 */
export function WinCard({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const w = t.report.win
  const form = data.fixed_metrics.recent_form
  const s = formSummary(form)
  /* 전체 승률과의 차이 — 전체 승률을 모르면(win_rate 없음) 비교하지 않는다 */
  const overall = data.fixed_metrics.win_rate
  const compare = typeof overall === 'number' && s.games > 0 && data.match_count > s.games ? { overall, gap: (s.rate - overall) * 100 } : null

  return (
    <section className="panel bg-surface p-[22px]" aria-label={w.title}>
      <PanelHead tag="WIN / LOSS" title={w.title} right={<Caption>{w.recent(s.games)}</Caption>} />

      <div className="flex items-center gap-2.5">
        <span className="inline-grid h-[26px] flex-none place-items-center rounded-md border-2 border-white bg-deep px-[11px] font-display text-[11px] font-extrabold tracking-[.12em] text-white">
          WIN
        </span>
        <span className="hp-track flex-1" aria-hidden="true">
          <i
            className="block h-full rounded bg-[linear-gradient(180deg,#A0F2DF_0_45%,#4FD0B4_45%_100%)] shadow-[inset_0_1px_0_rgba(255,255,255,.35)]"
            style={{ width: `${(s.rate * 100).toFixed(1)}%` }}
          />
        </span>
        <span className="num-hud flex-none text-[22px] text-white">
          <BigNum value={(s.rate * 100).toFixed(1)} suffix="%" />
        </span>
      </div>

      <div className="mt-3.5 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="font-num text-[30px] font-bold text-win">{s.wins}</span>
        <Caption>{w.w}</Caption>
        <span className="ml-3 font-num text-[30px] font-bold text-loss">{s.losses}</span>
        <Caption>{w.l}</Caption>
        {compare && (
          <span className="ml-auto flex items-baseline gap-2">
            <Caption>{w.overall(data.match_count, `${(compare.overall * 100).toFixed(1)}%`)}</Caption>
            <DeltaInline text={`${signed(compare.gap, 1)}%p`} trend={trendOf(compare.gap, 1)} />
          </span>
        )}
      </div>

      <div className="mt-5 border-t-2 border-[#24455E] pt-4">
        <div className="flex flex-wrap items-center gap-2.5">
          <PanelTag>RECENT {s.games}</PanelTag>
          <Caption>{w.boardHint}</Caption>
        </div>
        <div className="mt-3.5">
          {s.games ? <BlockBoard form={form} label={w.recent(s.games)} /> : <p className="m-0 text-sm text-muted">{w.empty}</p>}
        </div>
      </div>
    </section>
  )
}
