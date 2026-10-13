import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getHeavyStream } from '../api/client'
import type { HeavyChapterResult } from '../api/types'

export interface HeavyState {
  /** idle: 열지 않음 · streaming: 챕터를 받는 중 · done: 8개를 다 받음 · error: done 전에 끊김 */
  status: 'idle' | 'streaming' | 'done' | 'error'
  /** chapter_id → 결과. 도착한 것만 들어 있다 */
  chapters: Record<string, HeavyChapterResult>
  error?: ApiError
}

export type FootState = 'ok' | 'loading' | 'failed' | 'timeout'

/** 챕터 하나의 AI 각주 상태 — 아직 안 왔는데 스트림이 끝났거나 끊겼으면 실패로 본다 */
export function footStateOf(result: HeavyChapterResult | undefined, stream: HeavyState['status']): FootState {
  if (result) {
    if (result.status === 'ok' && result.footnote) return 'ok'
    return result.status === 'timeout' ? 'timeout' : 'failed'
  }
  return stream === 'streaming' ? 'loading' : 'failed'
}

/**
 * GET /comment?scope=heavy (SSE). dataKey가 null이면 열지 않는다 — 헤비 뷰이고 콜드스타트가 아닐 때만 넘긴다.
 * 이미 다 받은 dataKey면 다시 열지 않는다(라이트↔헤비를 오가도 LLM을 다시 부르지 않게).
 */
export function useHeavyComment(username: string, dataKey: string | null): HeavyState & { retry: () => void } {
  const [attempt, setAttempt] = useState(0)
  const key = dataKey && `${dataKey}~${attempt}`
  const [result, setResult] = useState<(HeavyState & { key: string }) | null>(null)
  const doneKey = useRef<string | null>(null)

  useEffect(() => {
    if (!key || doneKey.current === key) return
    const update = (fn: (prev: HeavyState) => HeavyState) =>
      setResult((prev) => ({ key, ...fn(prev?.key === key ? prev : { status: 'streaming', chapters: {} }) }))

    return getHeavyStream(username, {
      onChapter: (c) => update((prev) => ({ ...prev, chapters: { ...prev.chapters, [c.chapter_id]: c } })),
      onDone: () => {
        doneKey.current = key
        update((prev) => ({ ...prev, status: 'done' }))
      },
      onError: (error) => update((prev) => ({ ...prev, status: 'error', error })),
    })
  }, [username, key])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  const state: HeavyState = !key ? { status: 'idle', chapters: {} } : result?.key === key ? result : { status: 'streaming', chapters: {} }
  return { ...state, retry }
}
