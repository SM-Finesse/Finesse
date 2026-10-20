import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { num, skippedOf } from '../../lib/stats'

/** GAMES 줄 — 분석한 경기 수 · 수집했지만 뺀 경기 수. 라이트는 AI READOUT 코멘트 아래, 헤비는 프로필 아래 */
export function GamesLine({ data, className }: { data: StatsResponse; className?: string }) {
  const { t } = useI18n()
  const a = t.report.ai
  const skipped = skippedOf(data)
  return (
    <p className={cx('m-0 flex flex-wrap items-baseline gap-x-2 leading-tight', className)} title={skipped === null ? undefined : a.skippedWhy}>
      <span className="font-display text-[11px] font-bold tracking-[.18em] text-faint">GAMES</span>
      <span className="font-num text-[18px] font-bold text-head tabular-nums">{num(data.match_count)}</span>
      <span className="text-[13px] text-muted">
        {t.report.profile.gamesSub}
        {skipped !== null && ` · ${a.skipped(data.match_count + skipped, skipped)}`}
      </span>
    </p>
  )
}
