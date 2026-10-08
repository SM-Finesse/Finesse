import type { CSSProperties } from 'react'
import { cx } from '../../lib/cx'
import { levelTagKind } from '../../lib/levelTag'

const LAYER: CSSProperties = { position: 'absolute', top: 0, bottom: 0, zIndex: -1 }

/** TETR.IO 레벨 태그. 크기는 font-size로 정한다 — 모양이 전부 em 단위라 글자 크기에 맞춰 함께 커진다 */
export function LevelTag({ level, title, className }: { level: number; title?: string; className?: string }) {
  const k = levelTagKind(level)
  return (
    <span
      title={title}
      className={cx('relative isolate inline-block pt-[.18em] pr-[1em] pb-[.08em] pl-[.32em] font-num leading-none font-extrabold tabular-nums', className)}
      style={{ color: k.color, textShadow: k.shadow }}
    >
      <i aria-hidden="true" style={{ ...LAYER, left: 0, right: 0, background: k.badge, clipPath: k.badgeClip }} />
      <i aria-hidden="true" style={{ ...LAYER, left: 'calc(100% - 0.5em)', width: '1.5em', background: k.item, clipPath: k.itemClip }} />
      {level}
    </span>
  )
}
