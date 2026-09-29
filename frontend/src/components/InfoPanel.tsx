import { useEffect, useRef } from 'react'
import { useI18n } from '../i18n/context'

export const INFO_PANEL_ID = 'info-panel'

/** 느낌표 배지 — 검색창 바로 위, 누르면 이 사이트가 뭘 하는지 펼쳐진다 */
export function InfoBadge({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  const { t } = useI18n()
  return (
    <button
      type="button"
      id="info-badge"
      aria-expanded={open}
      aria-controls={INFO_PANEL_ID}
      aria-label={t.infoBadgeAria}
      onClick={onToggle}
      className="inline-flex h-[30px] items-center gap-[9px] rounded-full border-2 border-[#2C5573] bg-[#0E1E2B] pr-[13px] pl-1.5 font-display text-xs font-bold tracking-[.02em] text-muted transition-colors hover:border-frame hover:bg-[#153046] hover:text-head aria-expanded:border-frame aria-expanded:bg-[#153046] aria-expanded:text-head"
    >
      <span aria-hidden="true" className="grid size-5 flex-none place-items-center rounded-full bg-signal font-display text-[13px] leading-none font-extrabold text-[#0A1620]">
        !
      </span>
      {t.infoBadge}
    </button>
  )
}

/** 화면을 가리는 모달 대신 검색창 위에 끼워 넣는 설명 패널 */
export function InfoPanel({ onClose }: { onClose: () => void }) {
  const { t } = useI18n()
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => ref.current?.focus(), [])

  return (
    <div
      ref={ref}
      id={INFO_PANEL_ID}
      role="region"
      aria-label={t.infoRegion}
      tabIndex={-1}
      className="panel mb-3.5 animate-info-in rounded-[10px] border-2 bg-[#12212E] px-5 pt-[18px] pb-4 outline-none"
    >
      <div className="flex items-center gap-3">
        <span className="inline-flex h-[27px] items-center rounded-md border-2 border-white bg-frame px-[13px] font-display text-[11px] font-extrabold tracking-[.13em] text-[#06131C] shadow-[0_3px_0_rgba(0,0,0,.4)]">
          ABOUT
        </span>
        <span className="font-display text-base font-bold text-head">{t.infoTitle}</span>
        <button
          type="button"
          onClick={onClose}
          aria-label={t.close}
          className="ml-auto grid size-7 place-items-center rounded text-[19px] leading-none text-muted transition-colors hover:bg-[#1E3A52] hover:text-head"
        >
          ×
        </button>
      </div>
      <dl className="mt-[15px] grid grid-cols-[96px_minmax(0,1fr)] gap-x-4 gap-y-[9px] max-sm:grid-cols-1 max-sm:gap-y-1">
        {t.infoRows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="pt-[3px] font-display text-xs font-bold tracking-[.04em] text-primary-bright max-sm:pt-2">{k}</dt>
            <dd className="m-0 text-[14.5px] leading-[1.66] text-ink">{v}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-[15px] border-t border-dashed border-[#27485F] pt-3 text-xs text-faint">{t.disclaimer}</p>
    </div>
  )
}
