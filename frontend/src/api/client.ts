import type {
  ApiErrorBody,
  CommentScope,
  HeavyCommentResponse,
  LightCommentResponse,
  StatsResponse,
} from './types'

/*
 * 비워 두면 같은 출처(/api/...)로 요청한다 — 개발 중에는 Vite 프록시가, 배포 후에는 Nginx가 백엔드로 넘긴다.
 * 백엔드 CORS로 직접 붙고 싶으면 .env.local에 VITE_API_BASE_URL=http://localhost:8080
 */
const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

/** 서버에 닿지 못한 경우 status 0, code 'NETWORK_ERROR' */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, signal?: AbortSignal): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}${path}`, { headers: { Accept: 'application/json' }, signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다')
  }

  if (!res.ok) {
    /* 백엔드 공통 에러 포맷이 아니면(프록시 502, Tomcat 404 HTML 등) HTTP 상태만으로 판단 */
    const body = (await res.json().catch(() => null)) as Partial<ApiErrorBody> | null
    throw new ApiError(res.status, body?.error_code ?? `HTTP_${res.status}`, body?.message ?? res.statusText)
  }
  return (await res.json()) as T
}

const user = (username: string) => encodeURIComponent(username)

/** GET /api/v1/stats/{username} — refresh=true면 백엔드 캐시를 건너뛰고 TETR.IO를 다시 조회한다(전적 갱신) */
export function getStats(username: string, opts: { refresh?: boolean; signal?: AbortSignal } = {}) {
  const query = opts.refresh ? '?refresh=true' : ''
  return request<StatsResponse>(`/api/v1/stats/${user(username)}${query}`, opts.signal)
}

/** GET /api/v1/comment/{username}?scope=light|heavy — LLM 산출물이라 수십 초 걸릴 수 있다 */
export function getComment(username: string, scope: 'light', signal?: AbortSignal): Promise<LightCommentResponse>
export function getComment(username: string, scope: 'heavy', signal?: AbortSignal): Promise<HeavyCommentResponse>
export function getComment(username: string, scope: CommentScope, signal?: AbortSignal) {
  return request<LightCommentResponse | HeavyCommentResponse>(`/api/v1/comment/${user(username)}?scope=${scope}`, signal)
}
