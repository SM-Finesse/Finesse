import { lazy, Suspense, useState } from 'react'
import { LangProvider } from './i18n/LangProvider'
import { readStored, writeStored } from './lib/storage'
import { LandingPage } from './pages/LandingPage'
import type { AnalyzeRequest, View } from './types'

/* 결과 화면은 차트 라이브러리를 끌고 오므로 따로 나눠, 랜딩 첫 로딩을 가볍게 둔다 */
const ReportPage = lazy(() => import('./pages/ReportPage').then((m) => ({ default: m.ReportPage })))

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
        <Suspense fallback={<div className="min-h-screen" />}>
          <ReportPage username={request.username} view={view} onViewChange={changeView} onBack={() => setRequest(null)} />
        </Suspense>
      ) : (
        <LandingPage view={view} onViewChange={changeView} onAnalyze={analyze} initialUsername={lastUsername} />
      )}
    </LangProvider>
  )
}

export default App
