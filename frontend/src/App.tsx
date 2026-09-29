import { useState } from 'react'
import { LangProvider } from './i18n/LangProvider'
import { readStored, writeStored } from './lib/storage'
import { LandingPage } from './pages/LandingPage'
import { ReportPending } from './pages/ReportPending'
import type { AnalyzeRequest, View } from './types'

const VIEWS = ['light', 'heavy'] as const

function App() {
  /* 랜딩에서 고른 뷰가 결과 화면의 기본값이 된다 — 다음 방문에도 유지 */
  const [view, setView] = useState<View>(() => readStored('finesse.view', VIEWS, 'light'))
  const [request, setRequest] = useState<AnalyzeRequest | null>(null)
  /* 결과 화면에서 돌아왔을 때 방금 넣은 유저명을 다시 치지 않도록 */
  const [lastUsername, setLastUsername] = useState('')

  const changeView = (next: View) => {
    setView(next)
    writeStored('finesse.view', next)
  }

  const analyze = (req: AnalyzeRequest) => {
    setRequest(req)
    setLastUsername(req.username)
    window.scrollTo({ top: 0 })
  }

  return (
    <LangProvider>
      {request ? (
        <ReportPending request={request} onBack={() => setRequest(null)} />
      ) : (
        <LandingPage view={view} onViewChange={changeView} onAnalyze={analyze} initialUsername={lastUsername} />
      )}
    </LangProvider>
  )
}

export default App
