import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import type { HeavyChapterResult } from '../../../api/types'
import type { FootState } from '../../../hooks/useHeavyComment'
import { useI18n } from '../../../i18n/context'
import type { StatInfo } from '../../../i18n/strings'
import { cx } from '../../../lib/cx'
import { BigNum } from '../parts'

/** 근거 칩 한 개 — 이름과 값 */
export interface Chip {
  k: string
  v: string
}

/** 챕터 번호 칩 — 챕터 색 블록 */
export function ChapIdx({ no, color, className }: { no: string; color: string; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cx('grid size-[30px] flex-none place-items-center rounded-[2px] border font-mono text-xs font-medium text-[#101C26]', className)}
      style={{ background: color, borderColor: color, boxShadow: 'inset 0 3px 0 rgba(255,255,255,.24), inset 0 -3px 0 rgba(0,0,0,.28)' }}
    >
      {no}
    </span>
  )
}

/** 챕터 안 스코어 칸 — 이름 · 큰 값 · 설명. info가 있으면 오른쪽 위 ? 버튼으로 이 값이 뭔지 펼쳐 보인다 */
export function StatBox({ k, v, s, color, info }: { k: string; v: string; s?: string; color: string; info?: StatInfo }) {
  return (
    <div className="relative min-w-0 flex-[1_1_180px] rounded border border-line bg-surface-2 px-4 py-3.5">
      {info && <InfoTip name={k} info={info} />}
      <div className={cx('font-mono text-xs text-faint', info && 'pr-7')}>{k}</div>
      <div className="num-hud mt-1 truncate text-2xl tracking-[-.02em]" style={{ color }}>
        <BigNum value={v} />
      </div>
      {s && <div className="mt-[3px] text-[13px] text-muted">{s}</div>}
    </div>
  )
}

/** ? 버튼 + 설명 카드. 칸 안쪽 폭에 맞춰 띄워서 챕터 테두리에 잘리지 않는다 — Esc · 바깥 클릭 · 다시 누르면 닫힌다 */
function InfoTip({ name, info }: { name: string; info: StatInfo }) {
  const { t } = useI18n()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const id = useId()

  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={ref} className="contents">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        aria-label={t.report.heavy.about(name)}
        onClick={() => setOpen((o) => !o)}
        className="absolute top-2.5 right-2.5 grid size-[22px] place-items-center rounded-full border-2 border-[#2C5573] bg-[#0E1E2B] font-display text-xs leading-none font-extrabold text-muted transition-colors hover:border-frame hover:text-head aria-expanded:border-frame aria-expanded:bg-[#153046] aria-expanded:text-head"
      >
        ?
      </button>
      {open && (
        <div
          id={id}
          role="note"
          className="absolute inset-x-2 top-[40px] z-10 rounded-md border-2 border-frame bg-[#0E1E2B] px-3.5 py-3 text-[13px] leading-[1.6] text-ink shadow-[0_10px_24px_rgba(0,0,0,.55)]"
        >
          <p className="m-0">{info.lead}</p>
          {info.table && (
            <table className="mt-2.5 w-full border-collapse border border-[#27485F] text-[12.5px]">
              <caption className="pb-1.5 text-left font-display text-xs font-bold tracking-[.04em] text-primary-bright">{info.table.caption}</caption>
              <thead>
                <tr className="bg-[#153046] text-faint">
                  {info.table.head.map((th) => (
                    <th key={th} scope="col" className="px-2.5 py-1 text-left font-medium">
                      {th}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {info.table.rows.map(([fmt, gap]) => (
                  <tr key={fmt} className="border-t border-[#27485F]">
                    <th scope="row" className="px-2.5 py-1 text-left font-normal text-muted">
                      {fmt}
                    </th>
                    <td className="px-2.5 py-1 font-num font-bold text-head">{gap}</td>
                  </tr>
                ))}
              </tbody>
              {info.table.foot && (
                <tfoot>
                  <tr className="border-t border-[#27485F]">
                    <td colSpan={2} className="px-2.5 py-1 text-xs text-faint">
                      {info.table.foot}
                    </td>
                  </tr>
                </tfoot>
              )}
            </table>
          )}
          <dl className="m-0 mt-2.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 border-t border-dashed border-[#27485F] pt-2.5">
            {info.rows.map(([label, text]) => (
              <div key={label} className="contents">
                <dt className="font-display text-xs leading-[1.6] font-bold tracking-[.04em] text-primary-bright">{label}</dt>
                <dd className="m-0 text-muted">{text}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </div>
  )
}

export function Strip({ children }: { children: ReactNode }) {
  return <div className="mb-4 flex flex-wrap gap-3.5">{children}</div>
}

const HAIRLINE = 'before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-[linear-gradient(90deg,transparent,rgba(255,255,255,.14)_12%,rgba(255,255,255,.14)_88%,transparent)]'

function Footnote({ result, state, chips }: { result?: HeavyChapterResult; state: FootState; chips: Chip[] }) {
  const { t } = useI18n()
  const f = t.report.heavy.foot
  const aiTag = (
    <span className="inline-grid h-6 flex-none place-items-center rounded bg-frame px-[11px] font-display text-[11px] font-extrabold tracking-[.14em] text-[#06131C] shadow-[0_2px_0_rgba(0,0,0,.32),inset_0_1px_0_rgba(255,255,255,.22)]">
      AI
    </span>
  )

  return (
    <div className="border-t-2 border-[#1E3A52] bg-[rgba(6,15,22,.55)] px-[22px] pt-[17px] pb-4">
      <div className="flex items-start gap-[13px]">
        {aiTag}
        {state === 'loading' ? (
          <span role="status" aria-label={f.loading} className="flex flex-1 items-center gap-2.5">
            <i aria-hidden="true" className="size-[7px] animate-blip rounded-[2px] bg-frame" />
            <span className="text-[13px] text-[#7A8A99]">{f.loading}</span>
            <span aria-hidden="true" className="skeleton block h-3.5 max-w-[300px] flex-1" />
          </span>
        ) : (
          <p className={cx('m-0 text-[15.5px] leading-[1.72] font-medium tracking-[-.004em]', state === 'ok' ? 'text-head' : 'text-muted')}>
            {state === 'ok' ? result?.footnote : state === 'timeout' ? f.timeout : f.failed}
          </p>
        )}
      </div>
      {state !== 'loading' && chips.length > 0 && (
        <div className={cx('relative mt-[13px] flex flex-wrap items-center gap-2 pt-3', HAIRLINE)}>
          <span className="mr-0.5 font-display text-[10px] font-extrabold tracking-[.16em] text-faint">{t.report.heavy.evidence}</span>
          <span className="flex flex-wrap gap-2">
            {chips.map((c) => (
              <span key={c.k} className="inline-flex h-7 items-center gap-[7px] rounded border-2 border-[#2C5573] bg-[#0E1E2B] px-[11px] text-xs text-muted">
                {c.k} <b className="font-num font-bold text-ink">{c.v}</b>
              </span>
            ))}
          </span>
        </div>
      )}
    </div>
  )
}

/** 헤비 챕터 카드 — 번호 · 영문 제목 · 한글 제목 · 오른쪽(배지·자세히 보기) / 본문 / AI 코멘트 · 근거 / 꼬리 */
export function Chapter({
  index,
  color,
  eyebrow,
  title,
  right,
  onDetail,
  chips,
  result,
  foot,
  tail,
  children,
}: {
  index: number
  color: string
  eyebrow: string
  title: string
  right?: ReactNode
  onDetail?: () => void
  chips: Chip[]
  result?: HeavyChapterResult
  foot: FootState
  tail?: ReactNode
  children: ReactNode
}) {
  const { t } = useI18n()
  const no = String(index + 1).padStart(2, '0')
  return (
    <section aria-label={title} className="panel overflow-hidden bg-[#12212E]" style={{ borderColor: color }}>
      <div className="flex flex-wrap items-center gap-3.5 border-b border-line px-5 py-[18px]">
        <ChapIdx no={no} color={color} />
        <div className="min-w-0">
          <p className="m-0 font-mono text-[10.5px] tracking-[.14em] text-faint uppercase">{eyebrow}</p>
          <h3 className="m-0 mt-[3px] font-display text-[19px] font-semibold tracking-[-.015em] text-head">{title}</h3>
        </div>
        <div className="ml-auto flex items-center gap-3">
          {right}
          {onDetail && (
            <button
              type="button"
              onClick={onDetail}
              className="rounded px-2.5 py-1.5 text-xs font-semibold text-primary-bright transition-colors hover:bg-primary-bright/14"
            >
              {t.report.heavy.detail.open}
            </button>
          )}
        </div>
      </div>
      <div className="p-5">{children}</div>
      <Footnote result={result} state={foot} chips={chips} />
      {tail && <div className="px-5 pt-[18px] pb-5">{tail}</div>}
    </section>
  )
}

/** 상세 보기 — 큰 차트 + 표. ESC · 바깥 클릭 · ✕로 닫는다. 열려 있는 동안 뒤의 단축키(ESC 처음으로 등)는 막는다 */
export function DetailModal({ no, eyebrow, title, onClose, children }: { no: string; eyebrow: string; title: string; onClose: () => void; children: ReactNode }) {
  const { t } = useI18n()
  const close = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Tab') return
      e.stopPropagation()
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      prev?.focus?.()
    }
  }, [onClose])

  return (
    <div className="fixed inset-0 z-60 grid place-items-center bg-[rgba(5,5,12,.74)] p-4 backdrop-blur-[3px] sm:p-10" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div role="dialog" aria-modal="true" aria-label={t.report.heavy.detail.title(title)} className="max-h-full w-[min(1040px,100%)] overflow-auto rounded-lg border border-line bg-surface shadow-[0_24px_60px_rgba(0,0,0,.6)]">
        <div className="sticky top-0 z-[5] flex items-center gap-3.5 rounded-t-lg border-b-2 border-frame bg-surface px-6 py-5 shadow-[0_10px_18px_-14px_rgba(0,0,0,.95)]">
          <span className="grid size-[30px] flex-none place-items-center rounded-[2px] border border-line bg-surface-2 font-mono text-xs text-primary-bright">{no}</span>
          <div>
            <p className="m-0 font-mono text-[10.5px] tracking-[.14em] text-faint uppercase">{eyebrow}</p>
            <p className="m-0 mt-[3px] font-display text-[19px] font-semibold text-head">{title}</p>
          </div>
          <button
            ref={close}
            type="button"
            aria-label={t.report.heavy.detail.close}
            onClick={onClose}
            className="ml-auto grid size-[34px] place-items-center rounded-lg text-muted transition-colors hover:bg-surface-2"
          >
            ✕
          </button>
        </div>
        <div className="p-6">{children}</div>
      </div>
    </div>
  )
}

/** 상세 보기 아래 표 — scroll을 주면 머리글을 붙인 채 몸통만 스크롤 */
export function DataTable({ head, rows, scroll, note }: { head: string[]; rows: (string | number)[][]; scroll?: number; note?: string }) {
  return (
    <>
      <div className={cx('mt-5 overflow-auto', scroll ? 'rounded-md border border-line' : null)} style={scroll ? { maxHeight: scroll } : undefined}>
        <table className="w-full border-separate border-spacing-0">
          <thead>
            <tr>
              {head.map((h) => (
                <th key={h} className={cx('px-3 pb-[11px] text-left text-[13px] font-medium text-faint', scroll ? 'sticky top-0 z-[2] bg-surface pt-[11px] shadow-[0_1px_0_var(--color-line)]' : null)}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="hover:bg-primary-bright/6">
                {r.map((c, j) => (
                  <td key={j} className={cx('border-t border-line-soft px-3 py-3 text-[15px]', typeof c === 'number' && 'font-num font-bold tabular-nums')}>
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {note && <p className="mt-2 mb-0 text-[13px] text-[#7A8A99]">{note}</p>}
    </>
  )
}
