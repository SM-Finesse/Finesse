import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { retryWait } from './retry'

describe('retryWait', () => {
  it('서버가 준 Retry-After를 따르고, 너무 길면 60초로 자른다', () => {
    expect(retryWait(new ApiError(503, 'SERVER_BUSY', '', 5))).toBe(5)
    expect(retryWait(new ApiError(503, 'SERVER_BUSY', '', 3600))).toBe(60)
  })

  it('SERVER_BUSY인데 Retry-After가 없으면(헤더가 막힌 경우 등) 5초', () => {
    expect(retryWait(new ApiError(503, 'SERVER_BUSY', ''))).toBe(5)
  })

  it('다른 에러는 바로 다시 시도할 수 있다', () => {
    expect(retryWait(new ApiError(404, 'USER_NOT_FOUND', ''))).toBe(0)
    expect(retryWait(new ApiError(0, 'NETWORK_ERROR', ''))).toBe(0)
  })
})
