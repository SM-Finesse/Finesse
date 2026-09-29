import { useCallback, useEffect, useState } from 'react'
import { ApiError, getStats } from '../api/client'
import type { StatsResponse } from '../api/types'

export type StatsState =
  | { status: 'loading' }
  | { status: 'success'; data: StatsResponse }
  | { status: 'error'; error: ApiError }

/** 유저명이 바뀌거나 reload()하면 다시 불러온다. 화면을 떠나면 진행 중인 요청을 취소한다 */
export function useStats(username: string): StatsState & { reload: () => void } {
  const [attempt, setAttempt] = useState(0)
  /* 결과를 요청 키와 함께 저장 — 키가 바뀌면 이전 결과는 무시되고 자동으로 loading이 된다 */
  const key = `${username}#${attempt}`
  const [result, setResult] = useState<{ key: string; state: StatsState } | null>(null)

  useEffect(() => {
    const ctrl = new AbortController()
    getStats(username, { signal: ctrl.signal })
      .then((data) => setResult({ key, state: { status: 'success', data } }))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        const error = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e))
        setResult({ key, state: { status: 'error', error } })
      })
    return () => ctrl.abort()
  }, [username, key])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])
  const state: StatsState = result?.key === key ? result.state : { status: 'loading' }
  return { ...state, reload }
}
