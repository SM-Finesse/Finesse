import { useState } from 'react'
import type { Badge } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { badgeUrl, groupBadges } from '../../lib/badges'
import { cx } from '../../lib/cx'
import { num } from '../../lib/stats'

const BADGE = 30

/** TETR.IO 서포터 띠 — 단계가 2 이상이면 ★를 (단계 - 1)개 붙인다 */
export function SupporterTag({ tier }: { tier: number }) {
  return (
    <span
      className="inline-flex h-[22px] flex-none items-center gap-1 bg-[#FF4E1C] px-3 font-display text-[11px] font-extrabold tracking-[.12em] whitespace-nowrap text-white [clip-path:polygon(7px_0,calc(100%-7px)_0,100%_50%,calc(100%-7px)_100%,7px_100%,0_50%)]"
    >
      SUPPORTER
      {tier > 1 && <span className="text-[10px] tracking-normal text-[#FFE27A]">{'★'.repeat(tier - 1)}</span>}
    </span>
  )
}

/** 이 유저를 친구로 추가한 플레이어 수 — ch.tetr.io 프로필의 하트 */
export function FriendCount({ count }: { count: number }) {
  const { t } = useI18n()
  return (
    <span title={t.report.friendCount} className="inline-flex flex-none items-center gap-1 font-num text-[15px] font-bold text-[#8FE3A0] tabular-nums">
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-[17px] fill-current">
        <path d="M12 21.4 10.6 20.1C5.4 15.4 2 12.3 2 8.5 2 5.4 4.4 3 7.5 3c1.7 0 3.4.8 4.5 2.1C13.1 3.8 14.8 3 16.5 3 19.6 3 22 5.4 22 8.5c0 3.8-3.4 6.9-8.6 11.6L12 21.4Z" />
      </svg>
      <span className="sr-only">{t.report.friendCount}</span>
      {num(count)}
    </span>
  )
}

function BadgeImg({ badge, stacked, onError }: { badge: Badge; stacked: boolean; onError: () => void }) {
  const { t, lang } = useI18n()
  const label = badge.label ?? badge.id
  const date = badge.ts ? new Date(badge.ts) : null
  const title = [label, badge.desc, date && !Number.isNaN(date.getTime()) && t.report.badgeAchieved(date.toLocaleDateString(lang))].filter(Boolean).join('\n\n')
  return (
    <img
      src={badgeUrl(badge.id)}
      alt={label}
      title={title}
      width={BADGE}
      height={BADGE}
      draggable={false}
      referrerPolicy="no-referrer"
      onError={onError}
      className={cx('block size-[30px] flex-none object-contain drop-shadow-[0_2px_0_rgba(0,0,0,.35)]', stacked && '-ml-[19px]')}
    />
  )
}

/** 프로필 배지 — 같은 group은 겹쳐 쌓는다. 그림을 못 불러온 배지는 뺀다 */
export function Badges({ badges }: { badges: Badge[] }) {
  const { t } = useI18n()
  const [failed, setFailed] = useState<ReadonlySet<string>>(new Set())
  const shown = badges.filter((b) => !failed.has(b.id))
  if (shown.length === 0) return null
  const fail = (id: string) => setFailed((prev) => new Set(prev).add(id))

  return (
    <ul aria-label={t.report.badges} className="m-0 flex list-none flex-wrap items-center gap-1.5 p-0">
      {groupBadges(shown).map((group) => (
        <li key={`${group[0].group ?? group[0].id}`} className="flex">
          {group.map((b, i) => (
            <BadgeImg key={`${b.id}-${i}`} badge={b} stacked={i > 0} onError={() => fail(b.id)} />
          ))}
        </li>
      ))}
    </ul>
  )
}
