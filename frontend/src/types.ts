export type View = 'light' | 'heavy'
export type Lang = 'ko' | 'en'

/** 랜딩 화면이 결과 화면으로 넘기는 값 — 검증을 통과한 유저명과 고른 뷰 */
export interface AnalyzeRequest {
  username: string
  view: View
}
