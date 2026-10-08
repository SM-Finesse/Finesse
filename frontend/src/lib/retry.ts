import type { ApiError } from '../api/client'

/** 서버가 몰려서(SERVER_BUSY) Retry-After를 안 줬을 때 기다릴 시간 — 백엔드 기본값과 같다 */
const BUSY_WAIT = 5
const MAX_WAIT = 60

/** 이 에러 뒤에 다시 시도하기 전 기다릴 초 — 서버가 알려 준 Retry-After를 따른다 */
export function retryWait(error: ApiError): number {
  const sec = error.retryAfter ?? (error.code === 'SERVER_BUSY' ? BUSY_WAIT : 0)
  return Math.min(Math.max(0, sec), MAX_WAIT)
}
