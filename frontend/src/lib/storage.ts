/* 사생활 보호 모드 등에서 localStorage 접근 자체가 throw할 수 있다 — 실패하면 기본값으로 동작한다 */
export function readStored<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const v = localStorage.getItem(key)
    return v !== null && (allowed as readonly string[]).includes(v) ? (v as T) : fallback
  } catch {
    return fallback
  }
}

export function writeStored(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* 저장 실패는 화면 동작에 영향이 없다 */
  }
}
