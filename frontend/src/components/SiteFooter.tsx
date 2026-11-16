import type { ReactNode } from 'react'
import { useI18n } from '../i18n/context'
import { useEngineHome } from '../lib/engineHome'

/* CommentEngine(서비스 선택 화면)의 원형 로고 */
function EngineMark() {
  return (
    <span
      aria-hidden="true"
      className="inline-block h-[22px] w-[22px] shrink-0 rounded-full"
      style={{
        background:
          'radial-gradient(circle at 35% 35%, #fff 0 18%, transparent 19%), conic-gradient(#ff5a5a 0 25%, #ffc93c 0 50%, #3cc4ff 0 75%, #7c4dff 0)',
      }}
    />
  )
}

export function SiteFooter({ keys }: { keys?: ReactNode }) {
  const { t } = useI18n()
  const engineHome = useEngineHome()
  return (
    <footer className="relative border-t-3 border-frame bg-deep pt-6 pb-[30px]">
      <div className="mx-auto w-[min(1200px,calc(100%-32px))] sm:w-[min(1200px,calc(100%-80px))]">
        <div className="flex flex-wrap items-center gap-x-[22px] gap-y-3">
          {keys && (
            <>
              <span className="flex flex-wrap items-center gap-[22px] font-mono text-[11px] tracking-[.1em] text-faint ko:tracking-normal">{keys}</span>
              <span className="hidden h-[18px] w-px bg-line sm:block" />
            </>
          )}
          <span className="text-[13px] text-[#7A8A99]">{t.disclaimer}</span>
          <span className="ml-auto flex items-center gap-[18px]">
            <span className="font-num text-[13px] font-bold tracking-[.1em] text-[#7A8A99]">v0.1</span>
            {engineHome && (
              <a
                href={engineHome}
                title={t.engineHome}
                aria-label={t.engineHome}
                className="flex items-center gap-2 rounded-full py-1 pr-3 pl-1 text-[13px] font-bold text-[#C7D5E0] transition-colors hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-[#66C0F4]"
              >
                <EngineMark />
                CommentEngine
              </a>
            )}
          </span>
        </div>
        <p className="mt-3 border-t border-white/5 pt-3 text-[13px] leading-[1.65] text-[#7A8A99]">{t.footerRights}</p>
      </div>
    </footer>
  )
}
