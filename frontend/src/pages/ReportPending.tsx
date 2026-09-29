import type { ApiError } from '../api/client'
import type { StatsResponse } from '../api/types'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { useHotkeys } from '../hooks/useHotkeys'
import { useStats } from '../hooks/useStats'
import { useI18n } from '../i18n/context'
import type { Strings } from '../i18n/strings'
import type { AnalyzeRequest } from '../types'

const btnCls =
  'h-[38px] rounded border-2 border-[#5E90B0] bg-surface-2 px-3.5 text-sm text-ink shadow-[0_3px_0_rgba(0,0,0,.34)] transition-colors hover:bg-line hover:text-white'

/** 결과 화면 자리 — GET /stats를 불러와 기본 지표만 보여준다. 라이트/헤비 화면은 다음 단계 */
export function ReportPending({ request, onBack }: { request: AnalyzeRequest; onBack: () => void }) {
  const { t } = useI18n()
  const stats = useStats(request.username)
  useHotkeys((e) => {
    if (e.key === 'Escape') onBack()
  })

  return (
    <div className="flex min-h-screen flex-col">
      <SiteHeader onHome={onBack} />
      <main className="mx-auto w-[min(1200px,calc(100%-32px))] flex-1 py-16 sm:w-[min(1200px,calc(100%-80px))]">
        <div className="panel bg-surface p-6">
          <h2 className="m-0 font-display text-2xl font-bold text-head">{t.reportTitle(request.username)}</h2>

          {stats.status === 'loading' && (
            <p role="status" className="mt-4 flex items-center gap-2.5 text-[15px] text-muted">
              <span aria-hidden="true" className="size-4 animate-spin rounded-full border-2 border-line border-t-primary-bright" />
              {t.loading}
            </p>
          )}

          {stats.status === 'error' && (
            <div role="alert" className="mt-4">
              <p className="m-0 text-[15px] text-loss">{errorMessage(stats.error, t)}</p>
              <p className="mt-1 font-mono text-xs text-faint">{stats.error.code}</p>
              <button type="button" onClick={stats.reload} className={`${btnCls} mt-4`}>
                {t.retry}
              </button>
            </div>
          )}

          {stats.status === 'success' && <StatsSummary data={stats.data} t={t} />}

          <p className="mt-5 text-[15px] text-muted">{t.reportBody(t.view[request.view])}</p>
          <button type="button" onClick={onBack} className={`${btnCls} mt-5`}>
            {t.back}
          </button>
        </div>
      </main>
      <SiteFooter />
    </div>
  )
}

function StatsSummary({ data, t }: { data: StatsResponse; t: Strings }) {
  const rank = data.profile.rank && data.profile.rank !== 'z' ? data.profile.rank.toUpperCase() : t.unranked
  const items: [string, string][] = [
    [t.statLabels.rank, rank],
    [t.statLabels.tr, Math.round(data.profile.tr).toLocaleString()],
    [t.statLabels.winRate, `${(data.fixed_metrics.win_rate * 100).toFixed(1)}%`],
    [t.statLabels.matches, data.match_count.toLocaleString()],
  ]

  return (
    <>
      <dl className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {items.map(([label, value]) => (
          <div key={label} className="rounded-md border border-line bg-deep px-4 py-3">
            <dt className="text-xs text-muted">{label}</dt>
            <dd className="m-0 mt-1 font-num text-xl font-bold text-head tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
      {data.cold_start && (
        <p className="mt-4 rounded-md border border-signal/40 bg-signal/10 px-4 py-3 text-sm text-ink">{t.coldStart}</p>
      )}
    </>
  )
}

function errorMessage(error: ApiError, t: Strings): string {
  if (error.code === 'USER_NOT_FOUND') return t.statsErrors.notFound
  if (error.code === 'NETWORK_ERROR') return t.statsErrors.network
  if (error.code === 'TETRIO_API_UNAVAILABLE' || [502, 503, 504].includes(error.status)) return t.statsErrors.unavailable
  return t.statsErrors.generic
}
