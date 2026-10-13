import type { ReactNode } from 'react'
import { useI18n } from '../i18n/context'

export function SiteFooter({ keys }: { keys?: ReactNode }) {
  const { t } = useI18n()
  return (
    <footer className="relative border-t-3 border-frame bg-deep pt-6 pb-[30px]">
      <div className="mx-auto w-[min(1200px,calc(100%-32px))] sm:w-[min(1200px,calc(100%-80px))]">
        <div className="flex flex-wrap items-center gap-x-[22px] gap-y-3">
          {keys && (
            <>
              <span className="flex flex-wrap items-center gap-[22px] font-mono text-[11px] tracking-[.1em] text-faint">{keys}</span>
              <span className="hidden h-[18px] w-px bg-line sm:block" />
            </>
          )}
          <span className="text-[13px] text-[#7A8A99]">{t.disclaimer}</span>
          <span className="ml-auto font-num text-[13px] font-bold tracking-[.1em] text-[#7A8A99]">v0.1</span>
        </div>
        <p className="mt-3 border-t border-white/5 pt-3 text-[13px] leading-[1.65] text-[#7A8A99]">{t.footerRights}</p>
      </div>
    </footer>
  )
}
