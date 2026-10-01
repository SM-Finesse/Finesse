import type { ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { rankLabel } from '../../lib/stats'
import type { View } from '../../types'
import { Avatar } from './Avatar'
import { Caption } from './parts'

/** 리포트 머리 — 아바타 · 유저명 · 랭크 / 지금 보는 뷰 · 단축키 */
export function ReportHead({ data, view, keys, viewSwitch }: { data: StatsResponse; view: View; keys: ReactNode; viewSwitch: ReactNode }) {
  const { t } = useI18n()
  const r = t.report
  const rank = rankLabel(data.profile.rank)
  const tag = view === 'heavy' ? r.viewTag.heavy : data.cold_start ? r.viewTag.lightCold : r.viewTag.light

  return (
    <div className="mb-[22px] flex flex-wrap items-end gap-4">
      <div className="flex min-w-0 items-center gap-[18px]">
        <div className="size-[62px] flex-none rounded-lg border-3 border-white shadow-[0_4px_0_rgba(0,0,0,.42),0_14px_22px_-12px_rgba(0,0,0,.7)]">
          <Avatar name={data.username} size={56} />
        </div>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-[11px]">
            <h2 className="m-0 truncate font-display text-[26px] font-extrabold tracking-[.01em] text-head">{data.username}</h2>
            {rank && (
              <span className="rounded border-2 border-white bg-frame px-[9px] py-[3px] font-num text-[11px] font-bold text-[#06131C]">{rank}</span>
            )}
          </div>
          <Caption className="mt-1.5 block">{r.meta(data.match_count)}</Caption>
        </div>
      </div>
      <div className="ml-auto flex flex-col items-end gap-2">
        <span className="rounded border border-line bg-surface px-[11px] py-1 font-display text-[13px] text-muted">{tag}</span>
        {/* 좁은 화면에서는 헤더의 스위치를 숨기므로 여기서 뷰를 바꾼다 */}
        <span className="md:hidden">{viewSwitch}</span>
        <div className="flex flex-wrap items-center gap-4 font-mono text-[11px] tracking-[.1em] text-faint max-sm:hidden">{keys}</div>
      </div>
    </div>
  )
}
