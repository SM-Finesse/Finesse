import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { useHotkeys } from '../hooks/useHotkeys'
import { useI18n } from '../i18n/context'
import type { AnalyzeRequest } from '../types'

/** 결과 화면 자리 — 이번 단계는 유저명 받아오기까지. 받은 값이 제대로 넘어오는지만 보여준다 */
export function ReportPending({ request, onBack }: { request: AnalyzeRequest; onBack: () => void }) {
  const { t } = useI18n()
  useHotkeys((e) => {
    if (e.key === 'Escape') onBack()
  })

  return (
    <div className="flex min-h-screen flex-col">
      <SiteHeader onHome={onBack} />
      <main className="mx-auto w-[min(1200px,calc(100%-32px))] flex-1 py-16 sm:w-[min(1200px,calc(100%-80px))]">
        <div className="panel bg-surface p-6">
          <h2 className="m-0 font-display text-2xl font-bold text-head">{t.reportTitle(request.username)}</h2>
          <p className="mt-2 text-[15px] text-muted">{t.reportBody(t.view[request.view])}</p>
          <button
            type="button"
            onClick={onBack}
            className="mt-5 h-[38px] rounded border-2 border-[#5E90B0] bg-surface-2 px-3.5 text-sm text-ink shadow-[0_3px_0_rgba(0,0,0,.34)] transition-colors hover:bg-line hover:text-white"
          >
            {t.back}
          </button>
        </div>
      </main>
      <SiteFooter />
    </div>
  )
}
