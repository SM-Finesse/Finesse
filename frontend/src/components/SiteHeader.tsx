import type { ReactNode } from 'react'
import { useI18n } from '../i18n/context'
import { LogoMark } from './LogoMark'
import { LangSwitch } from './Switches'

/** actions가 있으면(결과 화면) OFFICIAL API 표시 대신 뷰 스위치·전적 갱신 같은 조작을 둔다 */
export function SiteHeader({ onHome, actions }: { onHome?: () => void; actions?: ReactNode }) {
  const { t } = useI18n()
  const logo = (
    <>
      <LogoMark className="size-[26px] flex-none" />
      FINESSE
    </>
  )
  const logoCls = 'flex items-center gap-2.5 font-display text-[19px] font-extrabold tracking-[.055em] text-head'
  return (
    <header className="sticky top-0 z-40 border-b-3 border-frame bg-deep">
      <div className="mx-auto flex h-[68px] w-[min(1200px,calc(100%-32px))] items-center gap-5 sm:w-[min(1200px,calc(100%-80px))]">
        {onHome ? (
          <button type="button" className={logoCls} onClick={onHome} aria-label={t.home}>{logo}</button>
        ) : (
          <div className={logoCls}>{logo}</div>
        )}
        <div className="ml-auto flex items-center gap-3.5">
          <LangSwitch />
          <span className={actions ? 'hidden text-[13px] text-[#7A8A99] xl:inline' : 'hidden text-[13px] text-[#7A8A99] lg:inline'}>{t.disclaimer}</span>
          {actions ?? (
            <span className="hidden items-center gap-[7px] rounded border border-line px-[9px] py-1 font-mono text-[10px] tracking-[.12em] text-faint sm:inline-flex">
              <i className="inline-block size-1.5 rounded-[1px] bg-win" />
              {t.officialApi}
            </span>
          )}
        </div>
      </div>
      <div className="piece-stripe" />
    </header>
  )
}
