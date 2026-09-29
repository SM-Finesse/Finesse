/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 비우면 같은 출처(/api) — Vite 프록시 · Nginx 경유 */
  readonly VITE_API_BASE_URL?: string
}
