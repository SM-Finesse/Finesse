import { useI18n } from '../../i18n/context'
import { num } from '../../lib/stats'

/** 이 유저를 친구로 추가한 플레이어 수 */
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
