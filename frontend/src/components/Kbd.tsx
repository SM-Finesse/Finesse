import type { ReactNode } from 'react'
import { cx } from '../lib/cx'

/** 키캡은 장식이 아니라 실제로 눌리는 키다. on = 지금 켜진 상태, hit = 방금 눌림 */
export function Kbd({ label, code, on, hit }: { label: string; code?: string; on?: boolean; hit?: boolean }) {
  return (
    <kbd
      data-k={code ?? label}
      className={cx(
        'mr-1 inline-grid h-[22px] min-w-[23px] place-items-center rounded border-2 border-b-4 px-1.5',
        'font-mono text-[11px] font-bold shadow-[inset_0_1px_0_rgba(255,255,255,.16)]',
        'transition-all duration-100 ease-(--ease-arcade)',
        hit
          ? 'translate-y-0.5 border-b-2 border-white bg-frame text-[#06131C] shadow-none'
          : on
            ? 'border-frame bg-[#16354C] text-[#BFE6FF]'
            : 'border-white bg-deep text-white',
      )}
    >
      {label}
    </kbd>
  )
}

export function KeyHint({ label, children }: { label: string; children?: ReactNode }) {
  return (
    <span className="inline-flex items-center">
      <b className="mr-[7px] font-medium">{label}</b>
      {children}
    </span>
  )
}
