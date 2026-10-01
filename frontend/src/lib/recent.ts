import { validateUsername } from './username'

/* 최근 검색 — 조회에 성공한 유저명만 이 브라우저에 남긴다(로그인이 없으므로 서버에는 저장하지 않는다) */
const KEY = 'finesse.recent'
export const RECENT_MAX = 5

const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()

export function readRecent(): string[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    if (!Array.isArray(raw)) return []
    /* 손으로 고친 값이나 예전 형식이 섞여 있어도 화면이 깨지지 않게 유효한 이름만 남긴다 */
    const out: string[] = []
    for (const v of raw) {
      if (typeof v !== 'string' || !validateUsername(v).ok || out.some((o) => same(o, v))) continue
      out.push(v)
    }
    return out.slice(0, RECENT_MAX)
  } catch {
    return []
  }
}

function write(list: string[]): string[] {
  try {
    localStorage.setItem(KEY, JSON.stringify(list))
  } catch {
    /* 저장 실패는 화면 동작에 영향이 없다 */
  }
  return list
}

/** 맨 앞에 넣는다. 대소문자만 다른 같은 이름은 하나로 친다 */
export function addRecent(list: string[], name: string): string[] {
  return write([name, ...list.filter((v) => !same(v, name))].slice(0, RECENT_MAX))
}

export function removeRecent(list: string[], name: string): string[] {
  return write(list.filter((v) => !same(v, name)))
}
