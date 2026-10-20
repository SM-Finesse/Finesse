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
  /** 503 SERVER_BUSY 등에서 서버가 알려 준 대기 시간(초) — Retry-After 헤더, 없으면 본문의 retry_after_seconds */
  readonly retryAfter?: number

  constructor(status: number, code: string, message: string, retryAfter?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfter = retryAfter
  }
}

/** Retry-After — 초(숫자) 또는 HTTP 날짜. 읽을 수 없으면 undefined */
export function parseRetryAfter(value: string | null, now = Date.now()): number | undefined {
  if (!value) return undefined
  const v = value.trim()
  if (/^\d+$/.test(v)) return Number(v)
  const at = Date.parse(v)
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - now) / 1000))
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
    throw new ApiError(
      res.status,
      body?.error_code ?? `HTTP_${res.status}`,
      body?.message ?? res.statusText,
      parseRetryAfter(res.headers.get('Retry-After')) ?? body?.retry_after_seconds,
    )
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
  /**
   * done 전에 끝남. 서버가 시작 전 실패를 event: error {error_code, message}로 알리면 그 코드(예: SERVER_BUSY)가,
   * 연결이 끊기거나 연결 자체가 실패하면(404·502 등 HTTP 에러도 EventSource에선 여기로 온다) STREAM_ERROR가 온다.
   */
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
  /* 서버가 보낸 event: error는 data가 있는 MessageEvent, 연결 실패는 data 없는 Event — 같은 'error'로 온다 */
  es.addEventListener('error', (e) => {
    if (finished) return
    finish()
    handlers.onError(streamError(e))
  })

  return finish
}

function streamError(e: Event): ApiError {
  const data = e instanceof MessageEvent && typeof e.data === 'string' ? e.data : ''
  if (data) {
    try {
      const body = JSON.parse(data) as Partial<ApiErrorBody>
      if (body.error_code) return new ApiError(0, body.error_code, body.message ?? '', body.retry_after_seconds)
    } catch {
      /* 형식이 다르면 연결 끊김과 같이 다룬다 */
    }
  }
  return new ApiError(0, 'STREAM_ERROR', '코멘트 스트림 연결이 끊겼습니다')
}
