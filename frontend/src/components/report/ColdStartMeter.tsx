import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { COLD_START_GAMES, formSummary } from '../../lib/stats'
import { BigNum, Caption, PanelTag } from './parts'

type Cell = 'W' | 'L' | 'played' | 'next' | 'left'

/* 블록 칸 — 위는 밝게, 아래는 어둡게 깎아 테트리스 블록처럼 */
const BLOCK = 'shadow-[inset_0_3px_0_rgba(255,255,255,.28),inset_0_-3px_0_rgba(0,0,0,.22)]'
const CELL: Record<Cell, string> = {
  W: cx('bg-primary-bright', BLOCK),
  L: cx('bg-piece-z', BLOCK),
  /* 치렀지만 승패 기록이 오지 않은 판 */
  played: cx('bg-[#4A6178]', BLOCK),
  next: 'border-2 border-signal/80 bg-signal/10 text-signal',
  left: 'border-2 border-line/70 bg-deep/60 text-faint',
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <i aria-hidden="true" className={cx('block size-2.5 rounded-[2px]', color)} />
      {label}
    </span>
  )
}

/**
 * 표본 부족(콜드스타트)일 때 — 분석이 열리기까지 몇 판 남았는지.
 * 칸 하나가 한 경기: 이긴 판은 파랑, 진 판은 빨강(왼쪽이 가장 오래된 경기), 남은 칸은 번호만 둔다.
 * 테두리 노란색은 '표본이 모자라다'는 뜻으로만 쓴다(Notice와 같은 규칙).
 */
export function ColdStartMeter({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const c = t.report.cold
  const need = COLD_START_GAMES
  const done = Math.min(Math.max(0, data.match_count), need)
  const left = need - done
  /* recent_form은 index 0이 최신 — 오래된 것부터 왼쪽에 놓는다 */
  const oldestFirst = [...data.fixed_metrics.recent_form].reverse()
  const cells: Cell[] = Array.from({ length: need }, (_, i) => (i < done ? (oldestFirst[i] ?? 'played') : i === done ? 'next' : 'left'))
  const unknown = cells.includes('played')
  /* 승률은 승패 기록이 온 판만으로 — 칸과 같은 기준이어야 해서 fixed_metrics.win_rate는 쓰지 않는다 */
  const s = formSummary(oldestFirst.slice(0, done))

  return (
    <section className="panel border-signal/55 bg-surface p-[22px]" aria-label={c.title}>
      <div className="-mx-[22px] -mt-3 mb-5 flex flex-wrap items-center gap-3 pr-[22px]">
        <PanelTag>UNLOCK</PanelTag>
        <h3 className="m-0 font-display text-sm font-semibold text-ink">{c.title}</h3>
      </div>

      <div className="flex flex-wrap items-center gap-x-8 gap-y-4">
        <div className="flex-none">
          <p className="m-0 flex items-baseline gap-1.5">
            <span className="num-hud text-[44px] leading-none text-[#F2DE7A]">{left}</span>
            <span className="font-display text-base font-bold text-[#DCCB74]">{c.leftUnit}</span>
          </p>
          <Caption className="mt-1 block">{c.progress(done, need)}</Caption>
          <div role="group" aria-label={c.winRate} className="mt-2.5 flex items-baseline gap-2">
            <span className="font-display text-[9.5px] font-bold tracking-[.18em] text-faint">WIN RATE</span>
            <span className="font-num text-[17px] font-bold text-head tabular-nums">
              {s.games ? <BigNum value={(s.rate * 100).toFixed(1)} suffix="%" /> : '—'}
            </span>
            {s.games > 0 && <span className="text-[11.5px] text-muted">{t.report.profile.wl(s.wins, s.losses)}</span>}
          </div>
        </div>

        <div className="min-w-[260px] flex-1">
          <ol role="img" aria-label={c.progress(done, need)} className="m-0 grid list-none grid-cols-10 gap-1.5 p-0">
            {cells.map((cell, i) => (
              <li
                key={i}
                data-state={cell}
                className={cx('grid h-8 place-items-center rounded-[3px] font-num text-[11px] font-bold', CELL[cell])}
              >
                {(cell === 'next' || cell === 'left') && i + 1}
              </li>
            ))}
          </ol>
          <div className="mt-2.5 flex flex-wrap justify-end gap-x-4 gap-y-1 text-[11.5px] text-muted">
            <Legend color="bg-primary-bright" label={c.win} />
            <Legend color="bg-piece-z" label={c.loss} />
            {unknown && <Legend color="bg-[#4A6178]" label={c.unknown} />}
          </div>
        </div>
      </div>

      <p className="mt-4 mb-0 border-t border-line-soft pt-3.5 text-[13px] leading-[1.65] text-muted">{c.body(need)}</p>
    </section>
  )
}
