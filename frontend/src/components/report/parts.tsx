import type { ReactNode } from 'react'
import { cx } from '../../lib/cx'
import { TREND_MARK, type Trend } from '../../lib/stats'

/** HOLD / NEXT 같은 명패 라벨 */
export function PanelTag({ children, accent }: { children: ReactNode; accent?: boolean }) {
  return (
    <span
      className={cx(
        'inline-flex h-[27px] flex-none items-center rounded-md border-2 border-white px-[13px] whitespace-nowrap',
        'font-display text-[11px] font-extrabold tracking-[.13em]',
        'shadow-[0_3px_0_rgba(0,0,0,.4),inset_0_1px_0_rgba(255,255,255,.10)]',
        accent ? 'bg-frame text-[#06131C]' : 'bg-deep text-white',
      )}
    >
      {children}
    </span>
  )
}

/** 카드 머리 — 명패는 카드 왼쪽 끝에 붙이고, 오른쪽에 캡션·배지 */
export function PanelHead({ tag, title, right }: { tag: string; title?: string; right?: ReactNode }) {
  return (
    <div className="-mx-[22px] -mt-3 mb-4 flex flex-wrap items-center gap-3 pr-[22px]">
      <PanelTag>{tag}</PanelTag>
      {title && <h3 className="m-0 font-display text-sm font-semibold text-ink">{title}</h3>}
      {right && <span className="ml-auto flex items-center gap-2.5">{right}</span>}
    </div>
  )
}

export function Caption({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cx('text-[13px] leading-[1.65] text-[#7A8A99]', className)}>{children}</span>
}

/** 데이터 부족·실패 안내 — 노란색은 '표본이 모자라다'는 뜻으로만 쓴다 */
export function Notice({ title, children, icon = '!' }: { title: string; children?: ReactNode; icon?: string }) {
  return (
    <div className="flex items-start gap-3.5 rounded-[10px] border-2 border-signal/40 bg-signal/[.07] px-5 py-[18px]">
      <span aria-hidden="true" className="grid size-6 flex-none place-items-center rounded-[3px] border border-signal/45 bg-signal/15 text-sm font-bold text-signal">
        {icon}
      </span>
      <div className="min-w-0">
        <p className="m-0 text-[15px] font-semibold text-[#DCCB74]">{title}</p>
        {children && <p className="mt-1 mb-0 text-[13px] text-muted">{children}</p>}
      </div>
    </div>
  )
}

const PILL: Record<Trend, string> = {
  up: 'border-delta-up/35 bg-delta-up/12 text-[#B4E24A]',
  down: 'border-delta-down/40 bg-delta-down/14 text-[#E5837E]',
  even: 'border-delta-even/30 bg-delta-even/12 text-muted',
}

/** Δ 배지 — 색만으로 말하지 않도록 방향 기호를 항상 같이 쓴다 */
export function DeltaPill({ text, trend }: { text: string; trend: Trend }) {
  return (
    <span className={cx('inline-flex items-center gap-1.5 rounded-full border px-2.5 py-[3px] font-num text-[13px] font-bold whitespace-nowrap', PILL[trend])}>
      {text} <span aria-hidden="true">{TREND_MARK[trend]}</span>
    </span>
  )
}

const MARK_COLOR: Record<Trend, string> = { up: 'text-delta-up', down: 'text-[#E5837E]', even: 'text-faint' }

/** 작은 증감 배지 — 프로필 칸 값 아래. 옅은 배경·테두리 위에 색 기호 + 밝은 숫자 (프로토타입 .delta.sm) */
export function DeltaBadge({ text, trend }: { text: string; trend: Trend }) {
  return (
    <span className={cx('inline-flex items-center gap-[3px] rounded-[3px] border px-[7px] py-0.5 font-num text-[11px] font-bold whitespace-nowrap', PILL[trend])}>
      <i aria-hidden="true" className={cx('text-[9px] not-italic', MARK_COLOR[trend])}>{TREND_MARK[trend]}</i>
      <span className="text-head">{text}</span>
    </span>
  )
}

/** 테두리 없는 작은 증감 — 기호만 색을 입힌다 */
export function DeltaInline({ text, trend }: { text: string; trend: Trend }) {
  return (
    <span className="inline-flex items-baseline gap-1 font-num text-xs font-bold whitespace-nowrap text-ink">
      <i aria-hidden="true" className={cx('text-[9px] not-italic', MARK_COLOR[trend])}>{TREND_MARK[trend]}</i>
      {text}
    </span>
  )
}

/** 정수는 크게, 소수점 이하는 작게 — 게임 HUD 수치 표기 */
export function BigNum({ value, suffix }: { value: string; suffix?: string }) {
  const i = value.indexOf('.')
  const head = i < 0 ? value : value.slice(0, i)
  const tail = (i < 0 ? '' : value.slice(i)) + (suffix ?? '')
  return (
    <>
      {head}
      {tail && <span className="relative -top-[.02em] text-[.56em] text-muted">{tail}</span>}
    </>
  )
}
