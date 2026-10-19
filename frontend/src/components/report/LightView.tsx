import { useState } from 'react'
import type { StatsResponse } from '../../api/types'
import type { CommentState } from '../../hooks/useLightComment'
import { useI18n } from '../../i18n/context'
import { AiReadout } from './AiReadout'
import { ColdStartMeter } from './ColdStartMeter'
import { Highlights, type HighlightFocus } from './Highlights'
import { Caption, PanelTag } from './parts'
import { ProfilePanel } from './ProfilePanel'
import { TrTrendCard } from './TrTrendCard'
import { WinCard } from './WinCard'

/** 라이트 뷰(FR-02) — 고정 지표 + AI 총평 + 근거가 붙은 하이라이트 */
export function LightView({ data, comment, onRetryComment }: { data: StatsResponse; comment: CommentState; onRetryComment: () => void }) {
  const { t } = useI18n()
  /* 켜진 하이라이트 카드 — 총평 구절과 카드가 같이 쓴다. 마우스를 올린 것이 고정한 것보다 우선 */
  const [pinned, setPinned] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const focus: HighlightFocus = { active: hovered ?? pinned, pinned, hover: setHovered, pin: setPinned }
  return (
    <div className="flex flex-col gap-4">
      <ProfilePanel data={data} />
      {data.cold_start ? <ColdStartMeter data={data} /> : <AiReadout comment={comment} onRetry={onRetryComment} data={data} focus={focus} />}
      {/* 표본 부족이면 승패·TR 추이 카드는 빈 칸뿐이라 그리지 않는다 — 치른 경기는 미터 칸에 승패 색으로 보인다 */}
      {!data.cold_start && (
        <div className="grid gap-5 lg:grid-cols-2">
          <WinCard data={data} />
          <TrTrendCard data={data} />
        </div>
      )}
      <div className="mt-0.5 flex flex-wrap items-baseline gap-3">
        <PanelTag>HIGHLIGHTS</PanelTag>
        <Caption>{data.cold_start ? t.report.hl.coldCaption : t.report.hl.caption}</Caption>
      </div>
      <Highlights data={data} comment={comment} focus={focus} />
    </div>
  )
}
