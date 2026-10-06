import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getComment } from '../api/client'
import type { LightCommentResponse } from '../api/types'

export type CommentState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: LightCommentResponse }
  | { status: 'error'; error: ApiError }

/**
 * GET /comment?scope=light. stats가 먼저 그려진 뒤에 부른다(FR-04) — dataKey가 null이면 부르지 않는다.
 * 콜드스타트는 LLM을 부르지 않으므로 호출 쪽에서 null을 넘긴다.
 * 이미 받은 dataKey면 다시 부르지 않는다 — 헤비에 갔다 돌아와도 받아 둔 응답을 그대로 쓴다(FR-06).
 */
export function useLightComment(username: string, dataKey: string | null): CommentState & { retry: () => void } {
  const [attempt, setAttempt] = useState(0)
  const key = dataKey && `${dataKey}~${attempt}`
  const [result, setResult] = useState<{ key: string; state: CommentState } | null>(null)
  const doneKey = useRef<string | null>(null)

  useEffect(() => {
    if (!key || doneKey.current === key) return
    const ctrl = new AbortController()
    getComment(username, ctrl.signal)
      .then((data) => {
        doneKey.current = key
        setResult({ key, state: { status: 'success', data } })
      })
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        const error = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e))
        setResult({ key, state: { status: 'error', error } })
      })
    return () => ctrl.abort()
  }, [username, key])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  const state: CommentState = !key ? { status: 'idle' } : result?.key === key ? result.state : { status: 'loading' }
  return { ...state, retry }
}
