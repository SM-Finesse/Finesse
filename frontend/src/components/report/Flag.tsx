import { useEffect, useState } from 'react'

/*
 * 국기 — Windows는 국기 이모지를 글자(KR)로 그리므로 SVG를 쓴다(country-flag-icons, MIT · 국기는 공공 저작물).
 * 265개를 다 싣지 않도록 나라마다 따로 나눠 두고, 화면에 필요한 하나만 불러온다.
 */
const FLAGS = import.meta.glob<string>('/node_modules/country-flag-icons/3x2/*.svg', { query: '?url', import: 'default' })

function loaderOf(code: string) {
  return FLAGS[`/node_modules/country-flag-icons/3x2/${code.toUpperCase()}.svg`]
}

/** 국기 그림이 없는 코드(XM 같은 특수 계정)거나 불러오지 못하면 코드 글자로 보여준다 */
export function Flag({ code, label }: { code: string; label: string }) {
  const [loaded, setLoaded] = useState<{ code: string; url: string } | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const load = loaderOf(code)

  useEffect(() => {
    if (!load) return
    let alive = true
    load()
      .then((url) => alive && setLoaded({ code, url }))
      .catch(() => alive && setFailed(code))
    return () => {
      alive = false
    }
  }, [code, load])

  const url = loaded?.code === code ? loaded.url : null
  if (!load || failed === code) {
    return (
      <span title={label} aria-label={label} className="rounded border border-white/40 bg-surface-2 px-1.5 py-px font-num text-[11px] font-bold tracking-[.08em] text-ink">
        {code.toUpperCase()}
      </span>
    )
  }
  /* 불러오는 동안에도 같은 크기의 자리를 잡아 둔다 */
  return url ? (
    <img
      src={url}
      alt={label}
      title={label}
      width={30}
      height={20}
      onError={() => setFailed(code)}
      className="block h-5 w-[30px] flex-none rounded-[3px] object-cover shadow-[0_0_0_1px_rgba(255,255,255,.4),0_2px_0_rgba(0,0,0,.35)]"
    />
  ) : (
    <span role="img" aria-label={label} className="block h-5 w-[30px] flex-none rounded-[3px] bg-surface-2" />
  )
}
