import { createContext, useContext } from 'react'
import type { Lang } from '../types'
import { STRINGS, type Strings } from './strings'

export interface I18n {
  lang: Lang
  setLang: (lang: Lang) => void
  t: Strings
}

export const I18nContext = createContext<I18n>({
  lang: 'ko',
  setLang: () => {},
  t: STRINGS.ko,
})

export function useI18n(): I18n {
  return useContext(I18nContext)
}
