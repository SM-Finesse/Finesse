import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getComment, getStats } from './client'

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

  it('comment는 scope를 쿼리로 넘긴다', async () => {
    const fetchFn = mockFetch(() => json({ light_summary: '', highlights: [] }))
    await getComment('player', 'light')
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

  it('서버에 닿지 못하면 NETWORK_ERROR', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    const err = await getStats('player').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })
})
