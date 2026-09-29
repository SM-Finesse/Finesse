import type { ApiErrorBody, HeavyChapterResult, LightCommentResponse, StatsResponse } from './types'

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

/** GET /api/v1/comment/{username}?scope=light — LLM 산출물이라 수십 초 걸릴 수 있다 */
export function getComment(username: string, signal?: AbortSignal) {
  return request<LightCommentResponse>(`/api/v1/comment/${user(username)}?scope=light`, signal)
}

export interface HeavyStreamHandlers {
  /** 챕터 하나가 끝날 때마다 (도착 순서 = 완료 순서, 챕터 순서 아님) */
  onChapter: (chapter: HeavyChapterResult) => void
  /** 서버가 done을 보냄 — 정상 종료 */
  onDone: () => void
  /** done 전에 연결이 끊기거나 연결 자체가 실패함 (404·502 등 HTTP 에러도 EventSource에선 여기로 온다) */
  onError: (error: ApiError) => void
}

/**
 * GET /api/v1/comment/{username}?scope=heavy (SSE).
 * 반환값은 연결을 끊는 함수 — useEffect cleanup에 그대로 넘기면 된다.
 *
 * EventSource는 연결이 끊기면 스스로 재연결해 LLM 호출을 처음부터 다시 일으키므로,
 * done을 받거나 에러가 나면 즉시 close()해서 재연결을 막는다.
 */
export function getHeavyStream(username: string, handlers: HeavyStreamHandlers): () => void {
  const es = new EventSource(`${API_BASE}/api/v1/comment/${user(username)}?scope=heavy`)
  let finished = false
  const finish = () => {
    finished = true
    es.close()
  }

  es.addEventListener('chapter', (e) => {
    if (finished) return
    let chapter: HeavyChapterResult
    try {
      chapter = JSON.parse((e as MessageEvent<string>).data) as HeavyChapterResult
    } catch {
      finish()
      handlers.onError(new ApiError(0, 'STREAM_PARSE_ERROR', '챕터 데이터를 읽을 수 없습니다'))
      return
    }
    handlers.onChapter(chapter)
  })
  es.addEventListener('done', () => {
    if (finished) return
    finish()
    handlers.onDone()
  })
  es.addEventListener('error', () => {
    if (finished) return
    finish()
    handlers.onError(new ApiError(0, 'STREAM_ERROR', '코멘트 스트림 연결이 끊겼습니다'))
  })

  return finish
}
