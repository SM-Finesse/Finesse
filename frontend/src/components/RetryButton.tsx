import { useEffect, useState } from 'react'
import { useI18n } from '../i18n/context'

/**
 * 다시 시도 버튼 — 기다려야 하면 남은 초를 세다가 풀린다.
 * 몰린 서버에 곧바로 다시 요청이 쏟아지지 않게, 자동으로 다시 부르지는 않고 사용자가 누르게 한다.
 */
export function RetryButton({ wait, onRetry, className }: { wait: number; onRetry: () => void; className?: string }) {
  const { t } = useI18n()
  /* 남은 초는 마감 시각에서 계산한다 — 타이머가 늦게 불려도(백그라운드 탭 등) 실제 시간과 어긋나지 않는다 */
  const [until] = useState(() => Date.now() + wait * 1000)
  const [left, setLeft] = useState(wait)

  useEffect(() => {
    if (wait <= 0) return
    const id = setInterval(() => {
      const sec = Math.max(0, Math.ceil((until - Date.now()) / 1000))
      setLeft(sec)
      if (sec === 0) clearInterval(id)
    }, 250)
    return () => clearInterval(id)
  }, [until, wait])

  return (
    <button type="button" onClick={onRetry} disabled={left > 0} className={className}>
      {left > 0 ? t.retryIn(left) : t.retry}
    </button>
  )
}
