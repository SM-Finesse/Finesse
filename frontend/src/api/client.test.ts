import { afterEach, describe, expect, it, vi } from 'vitest'
import { FakeEventSource } from '../test/fixtures'
import { ApiError, getComment, getHeavyStream, getStats, parseRetryAfter } from './client'

function mockFetch(impl: () => Promise<Response>) {
  const fn = vi.fn<typeof fetch>(impl)
  vi.stubGlobal('fetch', fn)
  return fn
}

const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api client', () => {
  it('stats는 유저명을 인코딩해 /api/v1/stats로 요청하고 JSON을 돌려준다', async () => {
    const fetchFn = mockFetch(() => json({ username: 'a b' }))
    await expect(getStats('a b')).resolves.toEqual({ username: 'a b' })
    expect(fetchFn.mock.calls[0][0]).toBe('/api/v1/stats/a%20b')
  })

  it('전적 갱신이면 refresh=true를 붙인다', async () => {
    const fetchFn = mockFetch(() => json({}))
    await getStats('player', { refresh: true })
    expect(fetchFn.mock.calls[0][0]).toBe('/api/v1/stats/player?refresh=true')
  })

  it('light comment는 scope=light로 요청한다', async () => {
    const fetchFn = mockFetch(() => json({ light_summary: '', highlights: [] }))
    await getComment('player')
    expect(fetchFn.mock.calls[0][0]).toBe('/api/v1/comment/player?scope=light')
  })

  it('백엔드 공통 에러 포맷을 ApiError로 바꾼다', async () => {
    mockFetch(() => json({ error_code: 'USER_NOT_FOUND', message: '없음' }, 404))
    await expect(getStats('ghost')).rejects.toMatchObject({ status: 404, code: 'USER_NOT_FOUND', message: '없음' })
  })

  it('JSON이 아닌 에러 응답은 HTTP 상태로 코드를 만든다', async () => {
    mockFetch(() => Promise.resolve(new Response('<html>', { status: 502, statusText: 'Bad Gateway' })))
    await expect(getStats('player')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' })
  })

  it('503 SERVER_BUSY의 Retry-After(초)를 함께 넘긴다', async () => {
    mockFetch(() =>
      Promise.resolve(
        new Response(JSON.stringify({ error_code: 'SERVER_BUSY', message: '혼잡' }), { status: 503, headers: { 'Retry-After': '5' } }),
      ),
    )
    await expect(getStats('player')).rejects.toMatchObject({ status: 503, code: 'SERVER_BUSY', retryAfter: 5 })
  })

  it('Retry-After 헤더가 막혔으면 본문의 retry_after_seconds를 쓴다', async () => {
    mockFetch(() =>
      Promise.resolve(new Response(JSON.stringify({ error_code: 'SERVER_BUSY', message: '혼잡', retry_after_seconds: 5 }), { status: 503 })),
    )
    await expect(getStats('player')).rejects.toMatchObject({ code: 'SERVER_BUSY', retryAfter: 5 })
  })

  it('Retry-After는 초 또는 HTTP 날짜 — 읽을 수 없으면 undefined', () => {
    const now = Date.parse('2026-10-02T06:00:00Z')
    expect(parseRetryAfter('5', now)).toBe(5)
    expect(parseRetryAfter('Fri, 02 Oct 2026 06:00:07 GMT', now)).toBe(7)
    expect(parseRetryAfter('Fri, 02 Oct 2026 05:59:00 GMT', now)).toBe(0)
    expect(parseRetryAfter('soon', now)).toBeUndefined()
    expect(parseRetryAfter(null, now)).toBeUndefined()
  })

  it('서버에 닿지 못하면 NETWORK_ERROR', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    const err = await getStats('player').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })
})

function openStream() {
  vi.stubGlobal('EventSource', FakeEventSource)
  const handlers = { onChapter: vi.fn(), onDone: vi.fn(), onError: vi.fn() }
  const cancel = getHeavyStream('a b', handlers)
  return { es: FakeEventSource.last, handlers, cancel }
}

const chapter = (id: string) => JSON.stringify({ chapter_id: id, status: 'ok', footnote: '각주' })

describe('getHeavyStream (SSE)', () => {
  it('챕터를 도착하는 대로 넘기고, done을 받으면 연결을 닫는다', () => {
    const { es, handlers } = openStream()
    expect(es.url).toBe('/api/v1/comment/a%20b?scope=heavy')

    es.emit('chapter', chapter('attack'))
    es.emit('chapter', chapter('defense'))
    expect(handlers.onChapter.mock.calls.map(([c]) => c.chapter_id)).toEqual(['attack', 'defense'])
    expect(es.closed).toBe(false)

    es.emit('done')
    expect(handlers.onDone).toHaveBeenCalledOnce()
    expect(es.closed).toBe(true)
    expect(handlers.onError).not.toHaveBeenCalled()
  })

  it('done 전에 끊기면 onError를 부르고 자동 재연결을 막으려 닫는다', () => {
    const { es, handlers } = openStream()
    es.emit('chapter', chapter('attack'))
    es.emit('error')
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_ERROR' }))
    expect(es.closed).toBe(true)

    es.emit('chapter', chapter('defense'))
    expect(handlers.onChapter).toHaveBeenCalledOnce()
  })

  it('시작 전에 서버가 event: error로 실패를 알리면 그 코드를 넘기고 닫는다', () => {
    const { es, handlers } = openStream()
    es.emit('error', JSON.stringify({ error_code: 'SERVER_BUSY', message: '사용자가 많습니다', retry_after_seconds: 5 }))
    expect(handlers.onError).toHaveBeenCalledOnce()
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'SERVER_BUSY', message: '사용자가 많습니다', retryAfter: 5 }))
    expect(es.closed).toBe(true)

    /* 서버가 스트림을 닫으면서 오는 연결 끊김 error는 무시된다 */
    es.emit('error')
    expect(handlers.onError).toHaveBeenCalledOnce()
  })

  it('event: error의 data가 형식에 안 맞으면 연결 끊김으로 본다', () => {
    const { handlers } = openStream()
    FakeEventSource.last.emit('error', 'not json')
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_ERROR' }))
  })

  it('취소 함수를 부르면 닫히고 이후 이벤트는 무시한다', () => {
    const { es, handlers, cancel } = openStream()
    cancel()
    expect(es.closed).toBe(true)
    es.emit('chapter', chapter('attack'))
    es.emit('done')
    expect(handlers.onChapter).not.toHaveBeenCalled()
    expect(handlers.onDone).not.toHaveBeenCalled()
  })
})
