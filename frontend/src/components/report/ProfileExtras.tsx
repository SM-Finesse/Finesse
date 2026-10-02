import { useState } from 'react'
import type { FeaturedAchievement } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { achievementRank, frameUrl, iconStyle, wreathUrl } from '../../lib/achievements'
import { cx } from '../../lib/cx'
import { num } from '../../lib/stats'

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

function Medal({ a, onError }: { a: FeaturedAchievement; onError: () => void }) {
  const { t } = useI18n()
  const rank = achievementRank(a.rank)
  const wreath = wreathUrl(a)
  const place = a.pos >= 0 ? ` · #${num(a.pos + 1)}${a.total ? ` / ${num(a.total)}` : ''}` : ''
  const title = [a.name.toUpperCase(), a.object, `${t.report.achievementRanks[rank]}${place}`].filter(Boolean).join('\n')
  const img = 'absolute inset-0 size-full'
  return (
    <li title={title} className="relative size-16 flex-none drop-shadow-[0_3px_0_rgba(0,0,0,.35)]">
      <img src={frameUrl(a.rank)} alt="" referrerPolicy="no-referrer" draggable={false} onError={onError} className={img} />
      {wreath && <img src={wreath} alt="" referrerPolicy="no-referrer" draggable={false} className={img} />}
      {/* 아이콘 칸 — 테두리 안쪽 57%. 시트가 검은 그림이라 뒤집어 흰색으로 쓴다 */}
      <span
        aria-hidden="true"
        className={cx('absolute inset-[21.43%] opacity-80', rank === 'none' ? 'invert-[.7]' : 'invert')}
        style={iconStyle(a.k)}
      />
      <span className="sr-only">{title}</span>
    </li>
  )
}

/** 대표 업적 메달 — 유저가 프로필에 걸어 둔 순서대로. 등급 없는 업적과 테두리를 못 불러온 업적은 뺀다 (ch.tetr.io와 같음) */
export function FeaturedAchievements({ achievements }: { achievements: FeaturedAchievement[] }) {
  const { t } = useI18n()
  const [failed, setFailed] = useState<ReadonlySet<number>>(new Set())
  const shown = achievements.filter((a) => a.rank !== 0 && !failed.has(a.k))
  if (shown.length === 0) return null

  return (
    <ul aria-label={t.report.featuredAchievements} className="m-0 flex list-none flex-wrap items-center gap-2 p-0">
      {shown.map((a) => (
        <Medal key={a.k} a={a} onError={() => setFailed((prev) => new Set(prev).add(a.k))} />
      ))}
    </ul>
  )
}
