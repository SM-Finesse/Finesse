import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

/* jsdom은 scrollTo를 구현하지 않는다 */
window.scrollTo = vi.fn() as unknown as typeof window.scrollTo

/* jsdom에는 ResizeObserver가 없다 — Recharts의 responsive 차트가 크기를 잴 때 쓴다 */
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

afterEach(() => {
  cleanup()
  localStorage.clear()
})
