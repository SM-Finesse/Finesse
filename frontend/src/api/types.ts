/*
 * 백엔드 응답 스키마 — backend/dto/*.java와 1:1.
 * 백엔드 Jackson이 SNAKE_CASE + non_null이라 필드명은 snake_case이고, null인 값은 키 자체가 빠진다(→ optional).
 */

export interface StatsResponse {
  username: string
  cold_start: boolean
  match_count: number
  /** 백엔드가 TETR.IO에서 처음 수집한 시각(ISO-8601) — 캐시에서 꺼내도 그대로 */
  updated_at?: string
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
  /** 이번 시즌 최고 랭크 (TETR.IO summaries/league의 bestrank). 랭크를 받은 적 없으면 빠진다 */
  best_rank?: string
  /** tr·glicko·rd — 랭크 기록이 없으면 TETR.IO가 주는 -1이 그대로 온다 */
  tr: number
  glicko: number
  rd: number
  apm?: number
  pps?: number
  vs?: number
  /*
   * 아래 5개는 TETR.IO /users/{user}에서 온다. 그 호출이 실패하면 전부 빠진다.
   * 상대(라이벌) 사진은 마스킹 원칙 때문에 오지 않는다 — 검색한 본인 것만.
   */
  /** 사진을 올린 적 없는 유저는 빠진다 */
  avatar_url?: string
  /** 레벨 계산용. 시스템 계정처럼 -1이면 빠진다 */
  xp?: number
  /** 두 글자 국가 코드. 특수 계정은 XM 같은 값도 온다 */
  country?: string
  /** 가입 시각(ISO-8601) */
  joined_at?: string
  /** 총 플레이 시간(초) — 유저가 숨겼으면 TETR.IO가 주는 -1이 그대로 온다. 유저 정보 호출이 실패하면 빠진다 */
  play_time_seconds?: number
  /** 프로필 배지 — 유저 정보 호출이 실패하면 빠진다 */
  badges?: Badge[]
  /** 이 유저를 친구로 추가한 플레이어 수 */
  friend_count?: number
}

/** 프로필 배지 — 백엔드는 보내 주지만 화면에는 그리지 않는다 */
export interface Badge {
  id: string
  label?: string
  desc?: string
  group?: string
  /** 획득 시각(ISO-8601) */
  ts?: string
}

export interface FixedMetrics {
  /** 0~1 비율. 모를 때(콜드스타트에서 승패 기록이 없을 때)는 빠진다 */
  win_rate?: number
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
  /** 2판 이상 뒤지다 이긴 비율 */
  comeback_rate?: number
  /** 2판 이상 앞서다 진 비율 */
  comeback_rate_against?: number
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
  /** ISO-8601 — 아직 계산하지 않아 빠질 수 있다 */
  last_match_at?: string
}

export interface LightCommentResponse {
  light_summary: string
  /** stat은 StatsResponse.delta_metrics의 키와 대응 */
  highlights: { stat: string; sentence: string }[]
}

/**
 * heavy 코멘트는 SSE로 온다 — `chapter` 이벤트 1건 = 챕터 1개, 8개를 다 보내면 `done` 이벤트.
 * 마감까지 못 끝난 챕터도 status='timeout'으로 채워 보내므로 done 전에 항상 8건이 온다.
 */
export const HEAVY_CHAPTERS = ['tr_trend', 'playstyle', 'attack', 'defense', 'strength_split', 'comeback_rate', 'session_vs_slope', 'rivals'] as const
export type HeavyChapterId = (typeof HEAVY_CHAPTERS)[number]

export interface HeavyChapterResult {
  chapter_id: string
  status: 'ok' | 'failed' | 'timeout'
  footnote?: string
  attempt_count?: number
}

/** 공통 에러 응답 { error_code, message } */
export interface ApiErrorBody {
  error_code: string
  message: string
}
