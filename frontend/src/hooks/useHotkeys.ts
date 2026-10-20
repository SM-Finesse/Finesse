import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react'

/** 입력 중에는 단축키를 가로채지 않는다 — 유저명에 l, h가 들어갈 수 있다 */
export function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable
}

/** 버튼·링크에 포커스가 있으면 Enter는 그 요소의 동작이다 — 전역 단축키로 가로채지 않는다 */
export function isActivatable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.tagName === 'BUTTON' || target.tagName === 'A' || target.getAttribute('role') === 'switch'
}

export function useHotkeys(handler: (e: KeyboardEvent) => void): void {
  const onKey = useEffectEvent((e: KeyboardEvent) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return
    handler(e)
  })
  useEffect(() => {
    const listener = (e: KeyboardEvent) => onKey(e)
    document.addEventListener('keydown', listener)
    return () => document.removeEventListener('keydown', listener)
  }, [])
}

/** 키캡이 실제로 눌린 것처럼 잠깐 내려앉는다 */
export function useKeyFlash(duration = 200): [string | null, (code: string) => void] {
  const [hit, setHit] = useState<string | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => () => clearTimeout(timer.current), [])

  const flash = useCallback(
    (code: string) => {
      clearTimeout(timer.current)
      setHit(code)
      timer.current = setTimeout(() => setHit(null), duration)
    },
    [duration],
  )
  return [hit, flash]
}
