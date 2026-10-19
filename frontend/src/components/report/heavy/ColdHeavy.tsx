import { HEAVY_CHAPTERS, type HeavyChapterId, type StatsResponse } from '../../../api/types'
import { useI18n } from '../../../i18n/context'
import { COLD_START_GAMES } from '../../../lib/stats'
import { ColdStartMeter } from '../ColdStartMeter'
import { ProfilePanel } from '../ProfilePanel'
import { ChapIdx } from './Chapter'
import { CHAPTER_COLORS, EYEBROWS } from './meta'

/* 'opp' = 상대 스탯이 있어야 계산되는 챕터, 'sample' = 내 기록만으로 그리지만 표본이 모자란 챕터 */
const NEEDS: Record<HeavyChapterId, 'opp' | 'sample'> = {
  tr_trend: 'sample',
  playstyle: 'opp',
  attack: 'opp',
  defense: 'opp',
  strength_split: 'opp',
  comeback_rate: 'opp',
  session_vs_slope: 'sample',
  rivals: 'opp',
}

/** 표본 부족(FR-03) — 챕터를 열지 않고, 어떤 챕터가 왜 막혔는지만 보여준다 */
export function ColdHeavy({ data, onLight }: { data: StatsResponse; onLight: () => void }) {
  const { t } = useI18n()
  const h = t.report.heavy
  const c = h.cold

  return (
    <div className="flex flex-col">
      <div className="mb-6">
        <ProfilePanel data={data} />
      </div>
      {/* 라이트와 같은 UNLOCK 미터 — 몇 판 남았는지, 치른 판의 승패 */}
      <div className="mb-5">
        <ColdStartMeter data={data} body={c.meterBody(COLD_START_GAMES)} />
      </div>

      <section aria-label={c.lockedKr} className="panel overflow-hidden bg-[#12212E]">
        <div className="flex flex-wrap items-center gap-[13px] border-b border-line px-5 py-4">
          <span className="inline-flex h-[27px] flex-none items-center rounded-md border-2 border-white bg-deep px-[13px] font-display text-[11px] font-extrabold tracking-[.13em] whitespace-nowrap text-white shadow-[0_3px_0_rgba(0,0,0,.4),inset_0_1px_0_rgba(255,255,255,.10)]">
            {c.lockedTag}
          </span>
          <span className="text-[15px] font-semibold text-ink">{c.lockedKr}</span>
          <button
            type="button"
            onClick={onLight}
            className="ml-auto inline-flex h-[38px] items-center rounded border border-line bg-surface-2 px-3.5 text-sm text-ink transition-colors hover:border-[#3D6C8C] hover:bg-line hover:text-white"
          >
            {c.toLight}
          </button>
        </div>
        <div className="p-5">
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {HEAVY_CHAPTERS.map((id, i) => (
              <li key={id} className="grid grid-cols-[30px_minmax(0,1fr)_auto_auto] items-center gap-3.5 rounded-md bg-white/[.022] px-3 py-2.5 even:bg-white/[.048]">
                <ChapIdx no={String(i + 1).padStart(2, '0')} color={CHAPTER_COLORS[id]} className="opacity-[.42]" />
                <span className="flex min-w-0 flex-col gap-px">
                  <span className="font-mono text-[10.5px] tracking-[.14em] text-faint uppercase">{EYEBROWS[id]}</span>
                  <span className="text-[15px] font-semibold text-[#93A8B8]">{h.chapters[id]}</span>
                </span>
                <span className="inline-grid h-5 place-items-center rounded border-2 border-signal/55 bg-signal/15 px-[9px] font-display text-[10px] font-extrabold tracking-[.08em] text-[#E6D27A]">
                  {NEEDS[id] === 'opp' ? c.needOpp : c.needSample}
                </span>
                <span className="font-display text-[10px] font-extrabold tracking-[.18em] text-faint">{c.locked}</span>
              </li>
            ))}
          </ul>
        </div>
        {/* AI가 만든 코멘트가 아니라 고정된 정책 설명이라 AI 배지를 달지 않는다 */}
        <div className="border-t-2 border-[#1E3A52] bg-[rgba(6,15,22,.55)] px-[22px] pt-[17px] pb-4">
          <div className="flex items-start gap-[13px]">
            <span
              role="img"
              aria-label={h.foot.warn}
              className="inline-grid h-6 min-w-6 flex-none place-items-center rounded-md border-2 border-signal/55 bg-signal/18 px-2 font-display text-[13px] leading-none font-extrabold text-[#DCCB74]"
            >
              !
            </span>
            <p className="m-0 text-[15.5px] leading-[1.72] font-medium text-head">{c.policy}</p>
          </div>
        </div>
      </section>
    </div>
  )
}
