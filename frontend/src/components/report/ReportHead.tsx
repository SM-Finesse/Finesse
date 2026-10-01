import { useState, type ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { countryName, levelFromXp, num, rankLabel, timeAgo } from '../../lib/stats'
import type { View } from '../../types'
import { Avatar } from './Avatar'
import { Flag } from './Flag'
import { Caption } from './parts'
import { RankIcon } from './RankIcon'

const SIZE = 88

/** 프로필 사진 — 없거나 못 불러오면 이름으로 만든 블록 아바타로 대신한다 */
function PlayerPhoto({ name, url }: { name: string; url?: string }) {
  const [failed, setFailed] = useState<string | null>(null)
  if (!url || failed === url) return <Avatar name={name} size={SIZE} />
  return (
    <img
      src={url}
      alt=""
      width={SIZE}
      height={SIZE}
      referrerPolicy="no-referrer"
      onError={() => setFailed(url)}
      className="block size-[88px] rounded-[6px] bg-deep object-cover"
    />
  )
}

/** 리포트 머리 — 사진 · 레벨 · 유저명 · 랭크 · 국가 / 지금 보는 뷰 · 단축키 */
export function ReportHead({ data, view, keys, viewSwitch }: { data: StatsResponse; view: View; keys: ReactNode; viewSwitch: ReactNode }) {
  const { t, lang } = useI18n()
  const r = t.report
  const p = data.profile
  const rank = rankLabel(p.rank)
  const lv = typeof p.xp === 'number' ? levelFromXp(p.xp) : null
  const joined = p.joined_at ? timeAgo(p.joined_at, lang) : null
  const updated = data.updated_at ? timeAgo(data.updated_at, lang) : null
  const tag = view === 'heavy' ? r.viewTag.heavy : data.cold_start ? r.viewTag.lightCold : r.viewTag.light
  const meta = [joined && r.joined(joined), r.games(data.match_count), updated && r.updated(updated), r.officialApi].filter(Boolean).join(' · ')

  return (
    <div className="mb-[22px] flex flex-wrap items-end gap-4">
      <div className="flex min-w-0 items-center gap-[22px]">
        <div className="relative size-[94px] flex-none rounded-[9px] border-3 border-white shadow-[0_5px_0_rgba(0,0,0,.42),0_16px_26px_-12px_rgba(0,0,0,.7)]">
          <PlayerPhoto name={data.username} url={p.avatar_url} />
          {lv && (
            <span className="absolute -bottom-[11px] -left-[11px] flex h-[26px] items-center gap-1.5 rounded-[6px] border-2 border-white bg-deep px-2.5 font-num text-[13px] font-bold whitespace-nowrap text-white shadow-[0_2px_0_rgba(0,0,0,.45)]">
              LV {num(lv.level)}
              <i aria-hidden="true" className="block h-3.5 w-[3px] -skew-x-[18deg] rounded-[1px] bg-loss" />
            </span>
          )}
        </div>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-[11px]">
            <h2 className="m-0 truncate font-display text-[30px] font-extrabold tracking-[.01em] text-head">{data.username}</h2>
            {rank && <RankIcon rank={p.rank} size={30} label={`RANK ${rank}`} />}
            {p.country && <Flag code={p.country} label={countryName(p.country, lang)} />}
          </div>
          {lv && p.xp !== undefined && (
            <div className="mt-2 flex items-center gap-2.5" title={r.xpProgress(Math.floor(lv.progress * 100))}>
              <span className="font-num text-[12.5px] font-bold tracking-[.02em] text-ink">
                {num(p.xp)} <em className="text-[10px] font-bold tracking-[.14em] text-faint not-italic">XP</em>
              </span>
              <span className="h-2 w-[140px] rounded-[3px] border border-line bg-deep p-px" aria-hidden="true">
                <i
                  className="block h-full rounded-[2px] bg-[linear-gradient(90deg,var(--color-primary-deep),var(--color-primary-bright))]"
                  style={{ width: `${(lv.progress * 100).toFixed(1)}%` }}
                />
              </span>
            </div>
          )}
          <Caption className="mt-1.5 block">{meta}</Caption>
        </div>
      </div>
      <div className="ml-auto flex flex-col items-end gap-2">
        <span className="rounded border border-line bg-surface px-[11px] py-1 font-display text-[13px] text-muted">{tag}</span>
        {/* 좁은 화면에서는 헤더의 스위치를 숨기므로 여기서 뷰를 바꾼다 */}
        <span className="md:hidden">{viewSwitch}</span>
        <div className="flex flex-wrap items-center gap-4 font-mono text-[11px] tracking-[.1em] text-faint max-sm:hidden">{keys}</div>
      </div>
    </div>
  )
}
