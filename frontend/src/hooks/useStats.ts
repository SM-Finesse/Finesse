import { useCallback, useEffect, useState } from 'react'
import { ApiError, getStats } from '../api/client'
import type { StatsResponse } from '../api/types'

export type StatsState =
  | { status: 'loading' }
  | { status: 'success'; data: StatsResponse }
  | { status: 'error'; error: ApiError }

export interface UseStats {
  state: StatsState
  /** 성공한 응답마다 바뀐다 — 코멘트 같은 후속 요청이 이 값을 보고 다시 불러온다 */
  dataKey: string | null
  /** 전적 갱신 중 — 이전 결과를 그대로 보여주면서 기다린다 */
  refreshing: boolean
  /** 전적 갱신 실패 — 이전 결과는 남겨 둔다 */
  refreshError: ApiError | null
  reload: () => void
  refresh: () => void
}

const toApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)))

/**
 * GET /stats. 유저명이 바뀌거나 reload()하면 다시 불러오고, 화면을 떠나면 진행 중인 요청을 취소한다.
 * refresh()는 백엔드 캐시를 건너뛰고 TETR.IO를 다시 조회한다(전적 갱신, FR-11).
 */
export function useStats(username: string): UseStats {
  const [req, setReq] = useState({ n: 0, refresh: false })
  /* 결과를 요청 키와 함께 저장 — 키가 바뀌면 이전 결과는 무시되고 자동으로 loading이 된다 */
  const key = `${username}#${req.n}`
  const [result, setResult] = useState<{ key: string; state: StatsState } | null>(null)
  /* 갱신 중·갱신 실패 때 계속 보여줄 마지막 성공 결과 */
  const [last, setLast] = useState<{ key: string; data: StatsResponse } | null>(null)

  useEffect(() => {
    const ctrl = new AbortController()
    getStats(username, { refresh: req.refresh, signal: ctrl.signal })
      .then((data) => {
        setResult({ key, state: { status: 'success', data } })
        setLast({ key, data })
      })
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return
        setResult({ key, state: { status: 'error', error: toApiError(e) } })
      })
    return () => ctrl.abort()
  }, [username, key, req.refresh])

  const reload = useCallback(() => setReq((r) => ({ n: r.n + 1, refresh: false })), [])
  const refresh = useCallback(() => setReq((r) => ({ n: r.n + 1, refresh: true })), [])

  const current = result?.key === key ? result.state : null
  const prev = req.refresh && last && last.key.startsWith(`${username}#`) ? last : null

  if (current?.status === 'success') return { state: current, dataKey: key, refreshing: false, refreshError: null, reload, refresh }
  if (prev) {
    const state: StatsState = { status: 'success', data: prev.data }
    const refreshError = current?.status === 'error' ? current.error : null
    return { state, dataKey: prev.key, refreshing: current === null, refreshError, reload, refresh }
  }
  return { state: current ?? { status: 'loading' }, dataKey: null, refreshing: false, refreshError: null, reload, refresh }
}
