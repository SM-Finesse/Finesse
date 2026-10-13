import { useEffect, type RefObject } from 'react'

/**
 * 열린 설명 카드·팝업을 Esc · 바깥 클릭으로 닫는다.
 * Esc는 리포트의 '처음으로' 단축키이기도 해서, 열려 있으면 먼저(캡처 단계) 받아 카드만 닫고 더 퍼지지 않게 한다.
 */
export function useDismiss(open: boolean, close: () => void, ref: RefObject<HTMLElement | null>) {
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      close()
    }
    document.addEventListener('pointerdown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open, close, ref])
}
