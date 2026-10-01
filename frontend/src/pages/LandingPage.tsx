import { useRef, useState } from 'react'
import { FloorArt, SkyArt } from '../components/BackgroundArt'
import { InfoBadge, InfoPanel } from '../components/InfoPanel'
import { Kbd, KeyHint } from '../components/Kbd'
import { LogoMark } from '../components/LogoMark'
import { ModeCard } from '../components/ModeCard'
import { RecentSearches } from '../components/RecentSearches'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { UsernameForm } from '../components/UsernameForm'
import { isActivatable, isTyping, useHotkeys, useKeyFlash } from '../hooks/useHotkeys'
import { useI18n } from '../i18n/context'
import { validateUsername } from '../lib/username'
import type { AnalyzeRequest, View } from '../types'

interface Props {
  view: View
  onViewChange: (view: View) => void
  onAnalyze: (req: AnalyzeRequest) => void
  initialUsername?: string
  recent?: string[]
  onRemoveRecent?: (name: string) => void
}

export function LandingPage({ view, onViewChange, onAnalyze, initialUsername = '', recent = [], onRemoveRecent = () => {} }: Props) {
  const { t } = useI18n()
  const [query, setQuery] = useState(initialUsername)
  /* 제출을 한 번 시도한 뒤부터 입력마다 다시 검사한다 — 치는 도중에 빨간 글씨를 띄우지 않기 위해 */
  const [attempted, setAttempted] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  const [hit, flash] = useKeyFlash()
  const inputRef = useRef<HTMLInputElement>(null)
  const badgeRef = useRef<HTMLDivElement>(null)

  const check = validateUsername(query)
  const error = attempted && !check.ok ? check.error : null

  function submit() {
    setAttempted(true)
    if (!check.ok) {
      inputRef.current?.focus()
      return
    }
    onAnalyze({ username: check.value, view })
  }

  function searchRecent(name: string) {
    setQuery(name)
    onAnalyze({ username: name, view })
  }

  function closeInfo() {
    setInfoOpen(false)
    badgeRef.current?.querySelector('button')?.focus()
  }

  const toggleView = () => onViewChange(view === 'light' ? 'heavy' : 'light')

  /* 화면에 그려둔 키캡 그대로 동작한다 — ⏎ 분석 · L 라이트 · H 헤비 · ESC 입력 해제/설명 닫기 */
  useHotkeys((e) => {
    if (e.key === 'Escape') {
      if (isTyping(e.target)) (e.target as HTMLElement).blur()
      else if (infoOpen) closeInfo()
      return
    }
    if (isTyping(e.target)) return /* 검색창의 ⏎는 form submit이 처리한다 */
    if (e.key === 'Enter') {
      if (isActivatable(e.target)) return
      e.preventDefault()
      flash('ENTER')
      submit()
      return
    }
    const k = e.key.length === 1 ? e.key.toUpperCase() : ''
    if (k === 'L' || k === 'H') {
      onViewChange(k === 'L' ? 'light' : 'heavy')
      flash(k)
    }
  })

  const keyCaps = (
    <>
      <KeyHint label={t.keyAnalyze}><Kbd label="↵" code="ENTER" hit={hit === 'ENTER'} /></KeyHint>
      <KeyHint label={t.keyView}>
        <Kbd label="L" on={view === 'light'} hit={hit === 'L'} />
        <Kbd label="H" on={view === 'heavy'} hit={hit === 'H'} />
      </KeyHint>
    </>
  )

  return (
    <div className="relative flex min-h-screen flex-col">
      <SkyArt />
      <SiteHeader />

      <main className="relative flex-1 pb-[296px]">
        <section className="pt-[92px] pb-[54px] max-sm:pt-14">
          <div className="mx-auto flex w-[min(1200px,calc(100%-32px))] flex-col items-center sm:w-[min(1200px,calc(100%-80px))]">
            <h1 className="mb-[34px] flex items-center gap-4">
              <LogoMark className="size-[60px] flex-none rounded-lg shadow-[0_5px_0_rgba(0,0,0,.42),0_18px_30px_-14px_rgba(0,0,0,.8)] max-sm:size-11" />
              <span className="text-outline font-display text-[46px] font-extrabold tracking-[.09em] text-head max-sm:text-[34px]">FINESSE</span>
            </h1>

            <div className="w-full max-w-[740px]">
              <div ref={badgeRef} className="mb-2.5 flex items-center">
                <InfoBadge open={infoOpen} onToggle={() => (infoOpen ? closeInfo() : setInfoOpen(true))} />
              </div>
              {infoOpen && <InfoPanel onClose={closeInfo} />}

              <UsernameForm
                value={query}
                onChange={setQuery}
                onSubmit={submit}
                error={error}
                view={view}
                onToggleView={toggleView}
                inputRef={inputRef}
              />

              {/* 최근 검색은 왼쪽, 단축키 안내는 오른쪽 — 좁아지면 두 줄로 접힌다 */}
              <div className="mt-4 flex flex-wrap items-center justify-between gap-x-7 gap-y-4">
                <RecentSearches names={recent} onPick={searchRecent} onRemove={onRemoveRecent} />
                <div className="flex flex-wrap items-center justify-end gap-4 font-mono text-[11px] tracking-[.1em] text-faint max-sm:hidden">
                  {keyCaps}
                  <span>{t.noLogin}</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="mx-auto w-[min(1200px,calc(100%-32px))] sm:w-[min(1200px,calc(100%-80px))]" aria-labelledby="mode-select">
          <div className="mb-3.5 flex flex-wrap items-center gap-3">
            <span id="mode-select" className="inline-flex h-[27px] items-center rounded-md border-2 border-white bg-deep px-[13px] font-display text-[11px] font-extrabold tracking-[.13em] text-white shadow-[0_3px_0_rgba(0,0,0,.4),inset_0_1px_0_rgba(255,255,255,.10)]">
              MODE SELECT
            </span>
            <span className="text-[13px] text-[#7A8A99]">{t.modeHint}</span>
          </div>
          <div className="mt-1.5 grid grid-cols-1 gap-[18px] md:grid-cols-2">
            <ModeCard mode="light" selected={view === 'light'} onSelect={onViewChange} />
            <ModeCard mode="heavy" selected={view === 'heavy'} onSelect={onViewChange} />
          </div>
        </section>

        <FloorArt />
      </main>

      <SiteFooter keys={keyCaps} />
    </div>
  )
}
