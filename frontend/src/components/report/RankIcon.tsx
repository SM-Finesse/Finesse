import { cx } from '../../lib/cx'

/* 랭크 아이콘 — 파일 이름은 랭크 문자에서 +는 -plus, -는 -minus로 바꾼 것 (x+ → x-plus.png, s- → s-minus.png) */
const ICONS = import.meta.glob<string>('../../assets/ranks/*.png', { eager: true, import: 'default' })

function iconOf(rank: string | undefined): string {
  const name = (rank ?? 'z').toLowerCase().replace(/\+$/, '-plus').replace(/-$/, '-minus')
  return ICONS[`../../assets/ranks/${name}.png`] ?? ICONS['../../assets/ranks/z.png']
}

/** label을 비우면 옆에 같은 글자가 이미 있는 장식용 아이콘으로 본다 */
export function RankIcon({ rank, size, label = '', className }: { rank: string | undefined; size: number; label?: string; className?: string }) {
  return (
    <img
      src={iconOf(rank)}
      alt={label}
      aria-hidden={label ? undefined : true}
      title={label || undefined}
      width={size}
      height={size}
      draggable={false}
      className={cx('flex-none object-contain drop-shadow-[0_2px_0_rgba(0,0,0,.35)]', className)}
    />
  )
}
