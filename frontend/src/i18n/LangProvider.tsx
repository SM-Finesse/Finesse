import { useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Lang } from '../types'
import { readStored, writeStored } from '../lib/storage'
import { I18nContext } from './context'
import { STRINGS } from './strings'

const LANGS = ['ko', 'en'] as const

export function LangProvider({ children, initial }: { children: ReactNode; initial?: Lang }) {
  const [lang, setLangState] = useState<Lang>(() => initial ?? readStored('finesse.lang', LANGS, 'ko'))

  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const value = useMemo(
    () => ({
      lang,
      t: STRINGS[lang],
      setLang: (next: Lang) => {
        setLangState(next)
        writeStored('finesse.lang', next)
      },
    }),
    [lang],
  )

  return <I18nContext value={value}>{children}</I18nContext>
}
