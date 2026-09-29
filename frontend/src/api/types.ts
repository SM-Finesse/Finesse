/*
 * 백엔드 응답 스키마 — backend/dto/*.java와 1:1.
 * 백엔드 Jackson이 SNAKE_CASE + non_null이라 필드명은 snake_case이고, null인 값은 키 자체가 빠진다(→ optional).
 */

export interface StatsResponse {
  username: string
  cold_start: boolean
  match_count: number
  profile: Profile
  fixed_metrics: FixedMetrics
  /** 콜드스타트면 빠진다 */
  delta_metrics?: DeltaMetrics
  round_curves: RoundCurves
  rivals: Rivals
  /** 헤비 뷰 8챕터 데이터 — 백엔드 스키마 협의 전이라 자유 구조 */
  chapters?: Record<string, unknown>
}

export interface Profile {
  rank?: string
  tr: number
  glicko: number
  rd: number
}

export interface FixedMetrics {
  /** 0~1 비율 */
  win_rate: number
  tr_trend: number[]
  /** 최근 최대 40경기 'W' | 'L', index 0이 가장 최근 */
  recent_form: ('W' | 'L')[]
}

export interface DeltaMetrics {
  tr_trend_delta?: number
  playstyle_relative?: PlaystyleRelative
  attack?: { delta_app: number; delta_weighted_app: number }
  defense?: { delta_vs_apm: number; delta_cheese_index: number }
  strength_split?: number
  comeback_rate?: number
  session_vs_slope?: number
}

export interface PlaystyleRelative {
  delta_opener?: number
  delta_plonk?: number
  delta_stride?: number
  delta_inf_ds?: number
}

export interface RoundCurves {
  pps: number[]
  vs: number[]
}

export interface Rivals {
  items: RivalItem[]
  page: number
  page_size: number
  total: number
}

export interface RivalItem {
  /** 백엔드가 이미 마스킹한 문자열 — 프론트는 그대로 표시만 한다 */
  nickname_masked: string
  matches: number
  wins: number
  losses: number
  /** ISO-8601 */
  last_match_at: string
}

export interface LightCommentResponse {
  light_summary: string
  /** stat은 StatsResponse.delta_metrics의 키와 대응 */
  highlights: { stat: string; sentence: string }[]
}

export interface HeavyCommentResponse {
  chapters: {
    chapter_id: string
    status: 'ok' | 'failed' | 'timeout'
    footnote?: string
    attempt_count?: number
  }[]
}

/** 공통 에러 응답 { error_code, message } */
export interface ApiErrorBody {
  error_code: string
  message: string
}

export type CommentScope = 'light' | 'heavy'
