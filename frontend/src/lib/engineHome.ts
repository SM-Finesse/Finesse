import { useEffect, useState } from 'react'

/* MSA(CommentEngine)에서 띄울 때만 쓰는 "서비스 선택 화면" 주소.
   같은 이미지를 운영(모놀리식)과 MSA가 함께 쓰므로, 주소는 빌드가 아니라 실행 시 정한다.
   - 컨테이너: Nginx가 ENGINE_HOME_URL 환경변수로 /engine.json 을 돌려준다 (비어 있으면 로고 안 보임)
   - 개발(vite): VITE_ENGINE_HOME_URL 로 바로 지정 가능 */
const ENV_URL = (import.meta.env.VITE_ENGINE_HOME_URL ?? '').trim()

let cached: Promise<string> | null = null

function isHttpUrl(v: unknown): v is string {
  return typeof v === 'string' && /^https?:\/\/\S+$/.test(v.trim())
}

function loadEngineHome(): Promise<string> {
  if (ENV_URL) return Promise.resolve(isHttpUrl(ENV_URL) ? ENV_URL : '')
  if (!cached) {
    cached = fetch('/engine.json', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((j: { homeUrl?: unknown } | null) => (j && isHttpUrl(j.homeUrl) ? j.homeUrl.trim() : ''))
      .catch(() => '') // 설정 없음(운영, 개발 서버의 index.html 응답 등) → 로고 숨김
  }
  return cached
}

export function useEngineHome(): string {
  const [url, setUrl] = useState('')
  useEffect(() => {
    let alive = true
    loadEngineHome().then((u) => {
      if (alive) setUrl(u)
    })
    return () => {
      alive = false
    }
  }, [])
  return url
}
