import type { CommentState } from '../../hooks/useLightComment'
import { useI18n } from '../../i18n/context'
import { Caption, PanelTag } from './parts'

/** AI 총평 — stats가 먼저 그려진 뒤 도착한다(FR-04). 오는 동안은 같은 크기의 자리를 잡아 둔다 */
export function AiReadout({ comment, onRetry }: { comment: CommentState; onRetry: () => void }) {
  const { t } = useI18n()
  const a = t.report.ai
  if (comment.status === 'idle') return null

  let body
  if (comment.status === 'loading') {
    body = (
      <>
        <PanelTag>AI READOUT</PanelTag>
        <div role="status" aria-label={a.loading} className="min-w-[120px] flex-1">
          <div className="skeleton h-[18px] w-3/4" />
        </div>
        <Caption className="flex-none">{a.loading}</Caption>
      </>
    )
  } else if (comment.status === 'error') {
    const msg = comment.error.code === 'LLM_UNAVAILABLE' ? a.unavailable : a.failed
    body = (
      <>
        <PanelTag>AI READOUT</PanelTag>
        <p role="alert" className="m-0 flex-1 text-[15px] text-muted">{msg}</p>
        <button
          type="button"
          onClick={onRetry}
          className="h-[34px] rounded border-2 border-[#5E90B0] bg-surface-2 px-3 text-sm text-ink transition-colors hover:bg-line hover:text-white"
        >
          {t.retry}
        </button>
      </>
    )
  } else {
    body = (
      <>
        <PanelTag accent>AI READOUT</PanelTag>
        <p className="m-0 min-w-0 flex-1 text-base leading-[1.6] font-medium text-head">{comment.data.light_summary}</p>
        <Caption className="flex-none max-md:hidden">{a.hint}</Caption>
      </>
    )
  }

  return (
    <section className="panel grid grid-cols-[4px_minmax(0,1fr)] overflow-hidden bg-surface" aria-label="AI READOUT">
      <div className="bg-primary-bright/70" />
      <div className="flex flex-wrap items-center gap-3.5 px-[22px] py-[15px]">{body}</div>
    </section>
  )
}
