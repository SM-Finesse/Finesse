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
  /** 이 유저를 친구로 추가한 플레이어 수 */
  friend_count?: number
  /** 프로필 5칸 증감 배지 — 콜드스타트나 비교할 수 없으면 빠진다 */
  window_delta?: WindowDelta
}

/**
 * 최근 N판(recent_matches) 평균 vs 그 이전 판 평균의 변화율 % — 9.4 = +9.4%. WR도 %p가 아니라 %.
 * tr_delta_pct는 따로 빠질 수 있다 (매치 당시 TR이 없을 때).
 */
export interface WindowDelta {
  recent_matches: number
  tr_delta_pct?: number
  wr_delta_pct?: number
  apm_delta_pct?: number
  pps_delta_pct?: number
  vs_delta_pct?: number
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
  /** my_avg·opp_avg는 delta를 계산한 같은 경기들의 나·상대 평균 — my − opp = delta. 구버전 응답엔 없다 */
  attack?: { delta_app: number; delta_weighted_app: number; my_avg?: AttackAvg; opp_avg?: AttackAvg }
  defense?: { delta_vs_apm: number; delta_cheese_index: number; my_avg?: DefenseAvg; opp_avg?: DefenseAvg }
  strength_split?: number
  /** 크게 뒤지다 이긴 비율 — 기준은 선승 수의 절반(올림): 3선승 2판 · 5선승 3판 · 7선승 4판 */
  comeback_rate?: number
  /** 유리한 경기 중 진 비율 — 유리 기준은 역전 기회와 같은 형식별 점수 차(3선승 2 · 5선승 3 · 7선승 4) */
  comeback_rate_against?: number
  /** comeback_rate − comeback_rate_against. 둘 중 하나라도 없으면 빠진다 — 라이트 하이라이트 후보 */
  delta_comeback?: number
  /** 위 두 비율의 분자·분모 */
  comeback_samples?: ComebackSamples
  /** 상대와의 TR 차이 5등분 — Q1(가장 약한 상대) → Q5(가장 강한 상대). strength_split이 없으면 빠진다 */
  strength_quintiles?: StrengthQuintile[]
  /** tr_trend_delta의 근거 — 최근 N판(판수 × 0.3, 3~30판) 평균과 전체 평균. 차이가 tr_trend_delta */
  tr_trend_basis?: TrTrendBasis
  session_vs_slope?: number
}

export interface AttackAvg {
  app: number
  weighted_app: number
}

export interface DefenseAvg {
  vs_apm: number
  /** 음수도 나온다 */
  cheese_index: number
}

export interface StrengthQuintile {
  /** 1 = 가장 약한 상대 구간, 5 = 가장 강한 상대 구간 */
  quintile: number
  matches: number
  wins: number
  /** 0~1 비율 */
  win_rate: number
}

export interface TrTrendBasis {
  recent_matches: number
  total_matches: number
  recent_avg_tr: number
  overall_avg_tr: number
}

export interface ComebackSamples {
  /** 2판 이상 뒤진 경기 수 — comeback_rate의 분모 */
  comeback_opportunities: number
  /** 그중 이긴 경기 수 */
  comeback_won: number
  /** 2판 이상 앞선 경기 수 — comeback_rate_against의 분모 */
  comeback_against_opportunities: number
  /** 그중 진 경기 수 */
  comeback_against_allowed: number
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
  /** 라운드 순서별 라운드 수 — vs와 길이가 같다. 작을수록 평균이 흔들린다 */
  samples?: number[]
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
  /** 503 SERVER_BUSY와 heavy event: error에 같이 온다 — 다시 시도하기 전 기다릴 초 */
  retry_after_seconds?: number
}
