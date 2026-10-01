import { useEffect, useEffectEvent } from 'react'
import type { ApiError } from '../api/client'
import { FloorArt, SkyArt } from '../components/BackgroundArt'
import { Kbd, KeyHint } from '../components/Kbd'
import { LightView } from '../components/report/LightView'
import { Notice } from '../components/report/parts'
import { ProfilePanel } from '../components/report/ProfilePanel'
import { ReportHead } from '../components/report/ReportHead'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { ViewSwitch } from '../components/Switches'
import { isTyping, useHotkeys, useKeyFlash } from '../hooks/useHotkeys'
import { useLightComment } from '../hooks/useLightComment'
import { useStats } from '../hooks/useStats'
import { useI18n } from '../i18n/context'
import type { Strings } from '../i18n/strings'
import type { View } from '../types'

const btnCls =
  'inline-flex h-[38px] items-center gap-2 rounded border-2 border-[#5E90B0] bg-surface-2 px-3.5 text-sm whitespace-nowrap text-ink shadow-[0_3px_0_rgba(0,0,0,.34)] transition-colors hover:bg-line hover:text-white disabled:cursor-progress disabled:border-line disabled:text-faint disabled:hover:bg-surface-2'
const wrapCls = 'mx-auto w-[min(1200px,calc(100%-32px))] sm:w-[min(1200px,calc(100%-80px))]'

function errorMessage(error: ApiError, t: Strings): string {
  if (error.code === 'USER_NOT_FOUND') return t.statsErrors.notFound
  if (error.code === 'NETWORK_ERROR') return t.statsErrors.network
  if (error.code === 'TETRIO_API_UNAVAILABLE' || [502, 503, 504].includes(error.status)) return t.statsErrors.unavailable
  return t.statsErrors.generic
}

const Spinner = () => <span aria-hidden="true" className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />

interface Props {
  username: string
  view: View
  onViewChange: (view: View) => void
  onBack: () => void
  /** 조회에 성공한 유저명 — 최근 검색에 남긴다 */
  onFound?: (username: string) => void
}

/** 결과 화면 — GET /stats를 먼저 그리고, 라이트 코멘트는 도착하는 대로 채운다 */
export function ReportPage({ username, view, onViewChange, onBack, onFound }: Props) {
  const { t } = useI18n()
  const r = t.report
  const stats = useStats(username)
  const data = stats.state.status === 'success' ? stats.state.data : null
  const found = useEffectEvent((name: string) => onFound?.(name))
  const foundName = data?.username
  useEffect(() => {
    if (foundName) found(foundName)
  }, [foundName])
  /* 콜드스타트는 LLM을 부르지 않는다. 갱신 중에는 새 stats가 올 때까지 기다린다 */
  const comment = useLightComment(username, data && !data.cold_start && view === 'light' ? stats.dataKey : null)
  const [hit, flash] = useKeyFlash()
  const canRefresh = data !== null && !stats.refreshing

  const doRefresh = () => {
    if (!canRefresh) return
    stats.refresh()
  }

  /* ESC 처음으로 · L 라이트 · H 헤비 · R 전적 갱신 */
  useHotkeys((e) => {
    if (isTyping(e.target)) return
    if (e.key === 'Escape') {
      flash('ESC')
      onBack()
      return
    }
    const k = e.key.length === 1 ? e.key.toUpperCase() : ''
    if (k === 'L' || k === 'H') {
      onViewChange(k === 'L' ? 'light' : 'heavy')
      flash(k)
    } else if (k === 'R' && canRefresh) {
      flash('R')
      doRefresh()
    }
  })

  const viewKeys = (
    <KeyHint label={t.keyView}>
      <Kbd label="L" on={view === 'light'} hit={hit === 'L'} />
      <Kbd label="H" on={view === 'heavy'} hit={hit === 'H'} />
    </KeyHint>
  )
  const refreshKey = (
    <KeyHint label={r.keyRefresh}>
      <Kbd label="R" hit={hit === 'R'} />
    </KeyHint>
  )

  const viewSwitch = (
    <ViewSwitch view={view} onToggle={() => onViewChange(view === 'light' ? 'heavy' : 'light')} ariaLabel={r.viewSwitchAria} />
  )
  const actions = (
    <>
      <span className="max-md:hidden">{viewSwitch}</span>
      <button type="button" className={btnCls} onClick={doRefresh} disabled={!canRefresh}>
        {stats.refreshing ? (
          <>
            <Spinner />
            {r.refreshing}
          </>
        ) : (
          r.refresh
        )}
      </button>
    </>
  )

  let body
  if (stats.state.status === 'loading') {
    body = (
      <div className="panel bg-surface p-6">
        <h2 className="m-0 font-display text-2xl font-bold text-head">{username}</h2>
        <p role="status" className="mt-4 mb-0 flex items-center gap-2.5 text-[15px] text-muted">
          <span aria-hidden="true" className="size-4 animate-spin rounded-full border-2 border-line border-t-primary-bright" />
          {t.loading}
        </p>
      </div>
    )
  } else if (stats.state.status === 'error') {
    const { error } = stats.state
    body = (
      <div className="panel bg-surface p-6">
        <h2 className="m-0 font-display text-2xl font-bold text-head">{username}</h2>
        <div role="alert" className="mt-4">
          <p className="m-0 text-[15px] text-loss">{errorMessage(error, t)}</p>
          <p className="mt-1 mb-0 font-mono text-xs text-faint">{error.code}</p>
        </div>
        <div className="mt-5 flex flex-wrap gap-2.5">
          <button type="button" onClick={stats.reload} className={btnCls}>{t.retry}</button>
          <button type="button" onClick={onBack} className={btnCls}>{t.back}</button>
        </div>
      </div>
    )
  } else {
    const d = stats.state.data
    body = (
      <>
        <ReportHead data={d} view={view} keys={<>{viewKeys}{refreshKey}</>} viewSwitch={viewSwitch} />
        {stats.refreshError && (
          <div role="alert" className="mb-4">
            <Notice title={r.refreshFailed}>{errorMessage(stats.refreshError, t)}</Notice>
          </div>
        )}
        {view === 'light' ? (
          <LightView data={d} comment={comment} onRetryComment={comment.retry} />
        ) : (
          <div className="flex flex-col gap-4">
            <ProfilePanel data={d} />
            <div className="panel flex flex-wrap items-center gap-4 bg-surface p-[22px]">
              <div className="min-w-0 flex-1">
                <Notice title={r.heavy.title}>{r.heavy.body}</Notice>
              </div>
              <button type="button" className={btnCls} onClick={() => onViewChange('light')}>{r.heavy.toLight}</button>
            </div>
          </div>
        )}
      </>
    )
  }

  return (
    <div className="relative flex min-h-screen flex-col">
      <SkyArt />
      <SiteHeader onHome={onBack} actions={actions} />
      <main className="relative flex-1 pb-[296px]">
        <div className={`${wrapCls} relative pt-8`} aria-busy={stats.refreshing || undefined}>
          {body}
        </div>
        <FloorArt />
      </main>
      <SiteFooter
        keys={
          <>
            {viewKeys}
            {refreshKey}
            <KeyHint label={r.keyBack}>
              <Kbd label="ESC" hit={hit === 'ESC'} />
            </KeyHint>
          </>
        }
      />
    </div>
  )
}
