import type { HeavyChapterId } from '../api/types'
import type { Lang, View } from '../types'
import type { UsernameError } from '../lib/username'
import { USERNAME_MAX, USERNAME_MIN } from '../lib/username'

/** 스코어 칸 ? 버튼 설명 — 한 줄 정의 · 경기 형식별 기준 표 · 측정 기준 항목들 */
export interface StatInfo {
  lead: string
  /** 머리 증감 알약에서 — ▲·▼가 이 지표에서 무슨 뜻인지 */
  dir?: { up: string; down: string }
  table?: { caption: string; head: [string, string]; rows: [string, string][]; foot?: string }
  rows: [string, string][]
}

type Row = readonly [string, string]

export interface Strings {
  disclaimer: string
  officialApi: string
  home: string
  close: string

  infoBadge: string
  infoBadgeAria: string
  infoTitle: string
  infoRegion: string
  infoRows: readonly Row[]

  usernameLabel: string
  placeholder: string
  analyze: string
  viewSwitchAria: string
  view: Record<View, string>
  errors: Record<UsernameError, string>

  recentTitle: string
  recentEmpty: string
  recentRemove: (name: string) => string
  keyAnalyze: string
  keyView: string
  noLogin: string

  modeHint: string
  modeRows: Record<View, readonly Row[]>

  footerRights: string
  keyLight: string
  keyHeavy: string

  back: string
  loading: string
  retry: string
  /** 서버가 Retry-After로 기다리라고 한 동안 다시 시도 버튼에 남은 초를 보여준다 */
  retryIn: (sec: number) => string
  statsErrors: Record<'notFound' | 'busy' | 'unavailable' | 'network' | 'generic', string>
  report: ReportStrings
}

export interface ReportStrings {
  joined: (ago: string) => string
  /** 총 플레이 시간 — 시간 단위, 이미 천 단위 구분이 된 문자열 */
  playTime: (hours: string) => string
  games: (n: number) => string
  updated: (ago: string) => string
  officialApi: string
  xpProgress: (pct: number) => string
  viewTag: { light: string; lightCold: string; heavy: string }
  viewSwitchAria: string
  keyRefresh: string
  keyBack: string
  refresh: string
  refreshing: string
  refreshFailed: string
  unranked: string
  friendCount: string
  profile: {
    season: string
    trSub: string
    basis: string
    wl: (w: number, l: number) => string
    rd: (rd: string) => string
    gamesSub: string
    apmSub: string
    ppsSub: string
    vsSub: string
    /** 5칸 증감 배지가 무엇인지 — 최근 N판 vs 그 이전 판 */
    note: (recent: number) => string
  }
  ai: { loading: string; failed: string; unavailable: string; hint: string }
  win: {
    title: string
    recent: (n: number) => string
    w: string
    l: string
    overall: (n: number, rate: string) => string
    boardHint: string
    empty: string
  }
  tr: {
    title: string
    games: (n: number) => string
    high: string
    low: string
    change: string
    perGame: string
    missingTitle: string
    missingBody: string
  }
  /** 표본 부족(콜드스타트) — 분석이 열리기까지 남은 경기 수 */
  cold: {
    title: string
    /** 큰 숫자 옆 단위 — '8' + '판 남음' */
    leftUnit: string
    progress: (done: number, need: number) => string
    win: string
    loss: string
    /** 치렀지만 승패 기록이 오지 않은 판 */
    unknown: string
    winRate: string
    body: (need: number) => string
  }
  hl: {
    caption: string
    coldCaption: string
    evidence: string
    coldTitle: string
    coldBody: (n: number) => string
    noneTitle: string
    noneBody: string
    failed: string
  }
  heavy: {
    streamFailed: string
    foot: { loading: string; failed: string; timeout: string; warn: string }
    evidence: string
    noData: string
    detail: { open: string; close: string; title: (chapter: string) => string }
    /** 스코어 칸 ? 버튼 이름 */
    about: (name: string) => string
    /** 알약 설명 머리 — 증감이 거의 없을 때 */
    even: string
    /** 03·04 머리 알약 — ▲▼ 기준(무엇과 무엇을 비교) + 같은 챕터 다른 지표 */
    pillInfo: { app: StatInfo; vsapm: StatInfo }
    with: string
    chapters: Record<HeavyChapterId, string>
    trend: {
      legendTr: string
      /** 초록 구간 — ΔTR이 쓰는 최근 N판 */
      recentBand: (n: number) => string
      /** 머리 ΔTR 알약을 눌렀을 때 — 백엔드가 준 최근·전체 판수가 있으면 그 숫자로 */
      deltaInfo: (recent?: number, total?: number) => StatInfo
      avgOf: (n: number) => string
      missing: string
      game: string
      tableNote: (n: number) => string
      aria: string
    }
    playstyle: { right: string; metric: string; desc: string; aria: string }
    sub: Record<'app' | 'wapp' | 'vsapm' | 'cheese', string>
    subInfo: Record<'app' | 'wapp' | 'vsapm' | 'cheese', StatInfo>
    /** 03·04장 나 vs 상대 평균 막대 */
    compare: { me: string; opp: string; attackAria: string; defenseAria: string }
    split: {
      right: string
      sub: string
      chartAria: string
      chartNote: string
      weak: string
      strong: string
      games: (n: number) => string
      /** 자세히 보기 표 머리 */
      table: [string, string, string, string]
      /** Strength Split 칸 ? 버튼 */
      info: StatInfo
    }
    comeback: {
      rateSub: string
      allowedSub: string
      /** 표본 — "9번 중 5번" */
      /** 표본 — "9번 중 5번" */
      of: (total: number, hit: number) => string
      /** ? 버튼 설명 — 측정 기준까지 */
      rateInfo: StatInfo
      allowedInfo: StatInfo
      /** 머리 ΔComeback 알약 */
      netInfo: StatInfo
      mine: string
      allowed: string
      mineMark: string
      allowedMark: string
      note: (max: number) => string
      aria: string
    }
    condition: {
      avgVs: string
      ppsNorm: string
      first: string
      last: (round: number) => string
      ppsChange: string
      round: string
      pps: string
      /** 라운드 순서별 라운드 수 */
      samples: string
      head: (round: number, samples?: number) => string
      perRound: string
      /** 머리 VS Slope 알약 */
      slopeInfo: StatInfo
      rising: string
      falling: string
      flat: string
      aria: string
    }
    rivals: {
      right: (n: number) => string
      nemesis: string
      edge: string
      even: string
      repeat: string
      repeatSub: string
      people: (n: number) => string
      record: (w: number, l: number, pct: number) => string
      nemesisRule: string
      edgeRule: string
      evenRule: string
      noLink: string
      noLinkB: string
      prev: string
      next: string
      showing: (total: number, from: number, to: number) => string
      empty: string
      policyTitle: string
      policy: readonly (readonly [string, string])[]
      policyExamples: readonly string[]
    }
    cold: {
      /** UNLOCK 미터 아래 안내 */
      meterBody: (need: number) => string
      lockedTag: string
      lockedKr: string
      toLight: string
      needOpp: string
      needSample: string
      locked: string
      policy: string
    }
  }
}

const ko: Strings = {
  disclaimer: '비공식 도구입니다. TETR.IO · osk와 무관합니다.',
  officialApi: 'OFFICIAL API',
  home: '홈으로',
  close: '닫기',

  infoBadge: '이 사이트는?',
  infoBadgeAria: '이 사이트 설명 열기',
  infoTitle: 'Finesse가 하는 일',
  infoRegion: '이 사이트 설명',
  infoRows: [
    ['무엇을 하나요', 'TETR.IO 유저명만 넣으면 공개 전적을 불러와, 최근 경기에서 실제로 붙었던 상대의 값과 내 값을 나란히 비교해 줍니다.'],
    ['두 가지 깊이', '라이트는 30초, 헤비는 5분. 읽는 데 쓸 시간에 맞춰 고르면 됩니다.'],
    ['상대 정보', '상대 닉네임은 언제나 가운데를 가려서 보여주고, 상대 프로필이나 리플레이로 가는 링크는 제공하지 않습니다.'],
    ['로그인', '필요 없습니다. 공개된 전적만 사용합니다.'],
  ],

  usernameLabel: '유저명',
  placeholder: '유저명을 입력하세요',
  analyze: '분석하기',
  viewSwitchAria: '분석 범위 — 끄면 라이트, 켜면 헤비',
  view: { light: '라이트', heavy: '헤비' },
  errors: {
    empty: '유저명을 입력하세요.',
    tooShort: `유저명은 ${USERNAME_MIN}자 이상입니다.`,
    tooLong: `유저명은 ${USERNAME_MAX}자 이하입니다.`,
    invalidChars: '유저명에는 영문 · 숫자 · _ · - 만 쓸 수 있습니다.',
  },

  recentTitle: '최근 검색',
  recentEmpty: '검색한 유저가 여기에 쌓입니다',
  recentRemove: (name) => `${name} 최근 검색에서 지우기`,
  keyAnalyze: '분석',
  keyView: '뷰 전환',
  noLogin: '로그인 없이 유저명만',

  modeHint: '지금 고른 쪽이 결과 화면의 기본값이 됩니다',
  modeRows: {
    light: [
      ['30초', '읽는 데 걸리는 시간'],
      ['4블록', '승률 · TR 추이 · AI 총평 · 하이라이트 3'],
      ['2개', '차트'],
    ],
    heavy: [
      ['5분', '읽는 데 걸리는 시간'],
      ['8챕터', '전체 지표 · 챕터마다 AI 각주'],
      ['7개', '차트 + 자주 만난 상대'],
    ],
  },

  footerRights: '모든 전적 데이터의 권리는 원 서비스에 있습니다. 상대 닉네임은 수집 단계에서 부분 마스킹되어 저장되며, 프로필·리플레이 링크는 제공하지 않습니다.',
  keyLight: '라이트',
  keyHeavy: '헤비',

  back: '다른 유저 검색',

  loading: '전적을 불러오는 중입니다…',
  retry: '다시 시도',
  retryIn: (sec) => `${sec}초 후 다시 시도`,
  statsErrors: {
    notFound: '해당 유저를 찾을 수 없습니다. 유저명을 확인해 주세요.',
    busy: '사용자가 많습니다. 잠시 후 다시 시도해 주세요.',
    unavailable: '일시적으로 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.',
    network: '서버에 연결할 수 없습니다. 백엔드가 켜져 있는지 확인해 주세요.',
    generic: '전적을 불러오지 못했습니다.',
  },
  report: {
    joined: (ago) => `가입 ${ago}`,
    playTime: (hours) => `플레이 ${hours}시간`,
    games: (n) => `최근 ${n}경기`,
    updated: (ago) => `${ago} 갱신`,
    officialApi: '공식 API',
    xpProgress: (pct) => `다음 레벨까지 ${pct}%`,
    viewTag: { light: '라이트 · 고정 지표 + 하이라이트 3', lightCold: '라이트 · 표본 부족', heavy: '헤비 · 8개 챕터' },
    viewSwitchAria: '뷰 전환 — 끄면 라이트, 켜면 헤비',
    keyRefresh: '갱신',
    keyBack: '처음으로',
    refresh: '전적 갱신',
    refreshing: '전적을 불러오는 중입니다…',
    refreshFailed: '전적을 갱신하지 못했습니다. 이전 결과를 그대로 보여줍니다.',
    unranked: '언랭크 — RD가 100 이상이라 표시할 티어가 없습니다. 분석은 그대로 진행합니다.',
    friendCount: '이 유저를 친구로 추가한 플레이어 수',
    profile: {
      season: '현재 시즌 랭크',
      trSub: '시즌 대전 점수',
      basis: '비교 기준',
      wl: (w, l) => `${w}승 ${l}패`,
      rd: (rd) => `편차 ±${rd}`,
      gamesSub: '분석한 최근 경기',
      apmSub: '분당 공격',
      ppsSub: '초당 블록',
      vsSub: '종합 지표',
      note: (n) => `값 아래 증감은 최근 ${n}판 평균을 그 이전 경기 평균과 비교한 변화율(%)입니다.`,
    },
    ai: {
      loading: '코멘트 생성 중… 통계는 이미 표시됨',
      failed: '코멘트를 불러오지 못했습니다.',
      unavailable: '일시적으로 코멘트를 생성할 수 없습니다.',
      hint: '카드에 마우스를 올리면 근거가 켜집니다',
    },
    win: {
      title: '승패 분포',
      recent: (n) => `최근 ${n}경기`,
      w: '승',
      l: '패',
      overall: (n, rate) => `전체 ${n}경기 ${rate}`,
      boardHint: '아래에서 위로 쌓입니다 · 채워진 칸이 승리',
      empty: '최근 1년 안에 치른 랭크 경기가 없습니다.',
    },
    tr: {
      title: 'TR 추이',
      games: (n) => `최근 ${n}경기`,
      high: '구간 최고',
      low: '구간 최저',
      change: '전체 변동',
      perGame: '경기당 평균',
      missingTitle: 'TR 추이 데이터를 받지 못했습니다.',
      missingBody: '경기 기록은 있지만 서버가 TR 변화 값을 보내지 않았습니다. 잠시 뒤 전적 갱신을 눌러 보세요.',
    },
    cold: {
      title: '분석이 열리기까지',
      leftUnit: '판 남음',
      progress: (done, need) => `${need}판 중 ${done}판 완료`,
      win: '승',
      loss: '패',
      unknown: '결과 미수신',
      winRate: '지금까지 승률',
      body: (need) => `최근 1년 안의 테트라 리그 랭크 경기를 ${need}판 채우면 AI 총평과 강점·약점 하이라이트가 열립니다. 경기를 더 치른 뒤 전적 갱신(R)을 눌러 주세요.`,
    },
    hl: {
      caption: 'AI가 고른 지표 · 문장마다 근거가 된 수치',
      coldCaption: '데이터 부족',
      evidence: '근거',
      coldTitle: '최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.',
      coldBody: (n) => `최근 1년 안의 랭크 경기가 10경기 이상 쌓이면 상대 대비 강점·약점 분석을 시작합니다. 현재 ${n}경기 기록됨.`,
      noneTitle: '하이라이트를 생성하지 못했습니다.',
      noneBody: 'AI가 고른 지표를 이번 전적의 수치와 이을 수 없어 AI 총평만 보여드립니다.',
      failed: '하이라이트를 불러오지 못했습니다.',
    },
    heavy: {
      streamFailed: '챕터 코멘트 연결이 끊겼습니다. 받은 챕터는 그대로 둡니다.',
      foot: { loading: '코멘트를 생성하는 중입니다 · 챕터별 개별 호출', failed: '이 챕터의 코멘트를 만들지 못했습니다.', timeout: '시간 안에 이 챕터의 코멘트를 받지 못했습니다.', warn: '주의' },
      evidence: '근거',
      noData: '이 챕터를 계산할 데이터가 아직 없습니다.',
      detail: { open: '자세히 보기', close: '닫기', title: (c) => `${c} 상세` },
      about: (name) => `${name} 설명`,
      even: '거의 차이 없음',
      pillInfo: {
        app: {
          lead: '내 블록당 공격량이 그 경기에서 만난 상대보다 얼마나 많은지 · 많으면 ▲, 적으면 ▼',
          dir: { up: '상대보다 블록당 공격이 많음', down: '상대보다 블록당 공격이 적음' },
          rows: [
            ['나', '경기마다 내 APP (APM ÷ (PPS × 60))'],
            ['상대', '같은 경기에서 만난 상대의 APP'],
            ['계산', '경기마다 나 − 상대, 그 차이의 평균'],
            ['범위', '최근 1년 · 최대 300판, APM 0이거나 PPS 0.1 미만인 경기 제외'],
          ],
        },
        vsapm: {
          lead: '내 공격 대비 방어 비율이 그 경기에서 만난 상대보다 얼마나 높은지 · 방어 쪽이면 ▲, 공격 쪽이면 ▼',
          dir: { up: '상대보다 방어에 더 치중', down: '상대보다 공격에 더 치중' },
          rows: [
            ['나', '경기마다 내 VS ÷ APM'],
            ['상대', '같은 경기에서 만난 상대의 VS ÷ APM'],
            ['계산', '경기마다 나 − 상대, 그 차이의 평균'],
            ['범위', '최근 1년 · 최대 300판, APM 0이거나 PPS 0.1 미만인 경기 제외'],
          ],
        },
      },
      with: '함께',
      chapters: {
        tr_trend: 'TR · 능력치 추이',
        playstyle: '플레이스타일 상대비교',
        attack: '공격 효율',
        defense: '수비 · 가비지 처리',
        strength_split: '상대 강도별 승률',
        comeback_rate: '역전승 퍼포먼스',
        session_vs_slope: '경기 내 컨디션 변화',
        rivals: '자주 만난 상대',
      },
      trend: {
        legendTr: 'TR (실제 값)',
        recentBand: (n) => `최근 ${n}판 (ΔTR 기준)`,
        deltaInfo: (n, total) => ({
          lead: '최근 경기 평균 TR이 분석한 전체 경기 평균 TR보다 얼마나 높은지 · 오르면 ▲, 내리면 ▼',
          dir: { up: '최근 경기 TR이 전체 평균보다 높음', down: '최근 경기 TR이 전체 평균보다 낮음' },
          rows: [
            ['최근', `${n ? `최신 ${n}판 — ` : ''}TR이 있는 경기의 30% (최소 3판 · 최대 30판)`],
            ['전체', `${total ? `${total}판 — ` : ''}최근 1년 · 최대 300판, TR이 없는 경기 제외`],
            ['계산', '최근 평균 TR − 전체 평균 TR'],
          ],
        }),
        avgOf: (n) => `Avg of ${n}g`,
        missing: 'TR 추이 데이터를 받지 못해 ΔTR만 보여줍니다.',
        game: '경기',
        tableNote: (n) => `${n}경기 전부 · 표 안에서 스크롤`,
        aria: 'TR 추이',
      },
      playstyle: { right: '절대값 비노출 · Δ만 표시', metric: 'Metric', desc: '설명', aria: '상대 대비 편차' },
      sub: { app: '블록당 공격량', wapp: '공격 성향', vsapm: '공격 대비 방어 비율', cheese: '가비지 처리' },
      compare: { me: '나', opp: '상대 평균', attackAria: '공격 효율 — 나 대 상대 평균', defenseAria: '수비 · 가비지 처리 — 나 대 상대 평균' },
      /* 03·04장 칸 ? 버튼 — 정의는 데이터명세서 2절, 식은 calc 명세 FancyMathCalculator */
      subInfo: {
        app: {
          lead: '블록 하나를 놓을 때마다 상대에게 보내는 공격 줄 수 · 높을수록 적은 블록으로 많이 공격',
          dir: { up: '상대보다 블록당 공격이 많음', down: '상대보다 블록당 공격이 적음' },
          rows: [
            ['식', 'APM ÷ (PPS × 60)'],
            ['비교', '경기마다 내 값 − 그 경기 상대 값, 그 차이의 평균'],
            ['범위', '최근 1년 · 최대 300판, APM 0이거나 PPS 0.1 미만인 경기 제외'],
          ],
        },
        wapp: {
          lead: '공격 성향 · 공격적으로 플레이할수록 높고, 방어 성향(Cheese Index)이 높을수록 크게 낮아짐',
          dir: { up: '상대보다 공격적', down: '상대보다 덜 공격적' },
          rows: [
            ['식', 'APP에서 Cheese Index만큼 깎은 값'],
            ['비교', '경기마다 내 값 − 그 경기 상대 값, 그 차이의 평균'],
            ['범위', '최근 1년 · 최대 300판, APM 0이거나 PPS 0.1 미만인 경기 제외'],
          ],
        },
        vsapm: {
          lead: '공격 대비 방어 비율 · 공격보다 방어(가비지 처리)에 힘을 쏟을수록 높음',
          dir: { up: '상대보다 방어에 더 치중', down: '상대보다 공격에 더 치중' },
          rows: [
            ['식', 'VS ÷ APM'],
            ['VS', '(보낸 공격 줄 + 지운 가비지 줄) ÷ 시간(초) × 100'],
            ['비교', '경기마다 내 값 − 그 경기 상대 값, 그 차이의 평균'],
            ['참고', '+면 상대보다 방어 쪽, −면 공격 쪽 — 높다고 무조건 좋은 건 아님'],
          ],
        },
        cheese: {
          lead: '방어 성향 · 공격은 적고 가비지를 많이, 효율적으로 지울수록 높음',
          dir: { up: '상대보다 방어적', down: '상대보다 공격적' },
          rows: [
            ['식', 'DS/P × 150 + (VS/APM − 2) × 50 + (0.6 − APP) × 125'],
            ['DS/P', '블록 하나당 지운 가비지 줄'],
            ['비교', '경기마다 내 값 − 그 경기 상대 값, 그 차이의 평균'],
            ['참고', '+면 상대보다 방어적, −면 공격적 — 높다고 무조건 좋은 건 아님'],
          ],
        },
      },
      split: {
        right: '상대와의 TR 차이 5등분 · 양 끝 구간',
        sub: '가장 강한 상대 20% 승률 − 가장 약한 상대 20% 승률',
        chartAria: '상대와의 TR 차이 구간별 승률',
        chartNote: '상대와의 TR 차이로 경기를 5등분 · 왼쪽일수록 약한 상대, 오른쪽일수록 강한 상대',
        weak: '약한 상대',
        strong: '강한 상대',
        games: (n) => `${n}판`,
        table: ['구간', '경기', '승', '승률'],
        info: {
          lead: '강한 상대를 만났을 때 승률이 약한 상대 때보다 얼마나 떨어지는지 · 0에 가까울수록 덜 흔들림',
          table: {
            caption: '구간 나누는 법',
            head: ['구간', '상대'],
            rows: [
              ['Q1', '가장 약한 상대 20%'],
              ['Q2~Q4', '그 사이'],
              ['Q5', '가장 강한 상대 20%'],
            ],
            foot: '상대 TR − 내 TR(경기 당시) 순으로 줄 세워 경기 수를 5등분',
          },
          rows: [
            ['기준', '고정 TR 구간이 아니라 내 경기 안에서의 상대적 위치'],
            ['범위', '최근 1년 · 최대 300판, 경기 당시 TR이 있는 경기'],
            ['계산', 'Q5 승률 − Q1 승률'],
            ['제외', 'TR이 있는 경기가 5판 미만이면 표시 안 함'],
          ],
        },
      },
      comeback: {
        rateSub: '불리한 경기 중 역전승한 비율',
        allowedSub: '유리한 경기 중 역전패한 비율',
        of: (n, k) => `${n}번 중 ${k}번`,
        rateInfo: {
          lead: '크게 뒤진 경기를 끝내 이긴 비율 · 높을수록 좋음',
          table: {
            caption: '역전 기회 기준',
            head: ['형식', '뒤처진 라운드'],
            rows: [
              ['3선승', '2판+'],
              ['5선승', '3판+'],
              ['7선승', '4판+'],
            ],
            foot: '라운드 시작 직전 · 선승 수의 절반(올림)',
          },
          rows: [
            ['범위', '최근 1년 · 최대 300판'],
            ['제외', '형식 불명 · 조기 종료'],
            ['계산', '역전승 ÷ 역전 기회'],
            ['주의', '기회가 적으면 크게 흔들림'],
          ],
        },
        allowedInfo: {
          lead: '크게 앞선 경기를 끝내 진 비율 · 낮을수록 좋음',
          table: {
            caption: '역전 허용 기회 기준',
            head: ['형식', '앞선 라운드'],
            rows: [
              ['3선승', '2판+'],
              ['5선승', '3판+'],
              ['7선승', '4판+'],
            ],
            foot: '라운드 시작 직전 · 선승 수의 절반(올림)',
          },
          rows: [
            ['범위', '최근 1년 · 최대 300판'],
            ['제외', '형식 불명 · 조기 종료'],
            ['계산', '역전패 ÷ 역전 허용 기회'],
            ['주의', '기회가 적으면 크게 흔들림'],
          ],
        },
        mine: '내 역전승률',
        allowed: '역전 허용률',
        mineMark: '역전 성공 ▲',
        allowedMark: '역전 허용 ▼',
        note: (max) => `왼쪽은 뒤진 경기를 뒤집은 비율, 오른쪽은 앞선 경기를 뒤집힌 비율 (축 최대 ${max}%)`,
        aria: '역전승률과 역전 허용률 비교',
        netInfo: {
          lead: '내 역전승률이 내 역전 허용률보다 얼마나 높은지 · 높으면 ▲, 낮으면 ▼',
          dir: { up: '뒤집은 경기가 뒤집힌 경기보다 많음', down: '뒤집힌 경기가 뒤집은 경기보다 많음' },
          rows: [
            ['역전승률', '불리한 경기 중 끝내 이긴 비율'],
            ['역전 허용률', '유리한 경기 중 끝내 진 비율'],
            ['유불리', '라운드 시작 직전 점수 차 — 3선승 2판 · 5선승 3판 · 7선승 4판 이상'],
            ['계산', '역전승률 − 역전 허용률 (%p)'],
            ['범위', '최근 1년 · 최대 300판, 형식 불명·조기 종료 제외 · 기회가 0번인 쪽이 있으면 표시 안 함'],
          ],
        },
      },
      condition: {
        avgVs: '평균 VS',
        ppsNorm: 'PPS (정규화)',
        first: 'R1 평균 VS',
        last: (r) => `R${r} 평균 VS`,
        ppsChange: 'PPS 변화',
        round: 'Round',
        pps: 'PPS',
        samples: '라운드 수',
        head: (r, n) => (n === undefined ? `ROUND ${r}` : `ROUND ${r} · ${n}개`),
        perRound: '라운드당 VS 변화',
        slopeInfo: {
          lead: '한 경기 안에서 뒤 라운드로 갈수록 VS가 오르는지 · 오르면 ▲, 내리면 ▼',
          dir: { up: '라운드가 지날수록 VS가 오름', down: '라운드가 지날수록 VS가 내려감' },
          rows: [
            ['앞·뒤', '라운드 순서(R1, R2 …)마다 모든 경기의 VS를 평균'],
            ['계산', '그 평균들에 맞춘 직선의 기울기 = 라운드당 VS 변화'],
            ['범위', '최근 1년 · 최대 300판의 모든 라운드, 중간에 끝난 경기의 마지막 라운드 제외'],
            ['참고', '뒤쪽 라운드는 표본이 적어 흔들림 — 라운드 수는 자세히 보기 표에'],
          ],
        },
        rising: '라운드가 지날수록 VS가 오릅니다',
        falling: '라운드가 지날수록 VS가 내려갑니다',
        flat: '라운드가 지나도 VS가 거의 그대로입니다',
        aria: '라운드별 평균 VS',
      },
      rivals: {
        right: (n) => `${n}명 · 20명/페이지`,
        nemesis: '천적',
        edge: '우세',
        even: '접전',
        repeat: '반복 조우',
        repeatSub: '5경기 이상 만난 상대',
        people: (n) => `${n}명`,
        record: (w, l, p) => `${w}승 ${l}패 · ${p}%`,
        nemesisRule: '승률 40% 이하',
        edgeRule: '65% 이상',
        evenRule: '45–55%',
        noLink: '링크 없음',
        noLinkB: '프로필·리플레이 비노출',
        prev: '이전 페이지',
        next: '다음 페이지',
        showing: (total, from, to) => `${total}명 중 ${from}–${to}명 표시 · 조우 횟수 내림차순`,
        empty: '같은 상대를 만난 기록이 없습니다.',
        policyTitle: '상대 데이터 취급 방식',
        policy: [
          ['길이 보존 마스킹', '닉네임은 3~16자, 영문·숫자·_·-만 쓸 수 있습니다. 가릴 글자 수는 ⌊길이 ÷ 3⌋(최소 1자), 시작 위치는 ⌊(길이 − 가릴 글자 수) ÷ 2⌋ — 가운데를 덮되 전체 길이는 원본 그대로 둡니다.'],
          ['링크 비노출', '마스킹의 부속이 아닌 별도 요건입니다. 이 화면 어디에서도 상대의 프로필이나 리플레이로 바로 갈 수 없습니다. 표의 행은 클릭 대상이 아닙니다.'],
          ['프롬프트에도 적용', 'AI 코멘트를 만들 때 넘기는 값도 마스킹된 닉네임입니다. 원본 닉네임은 프롬프트에 넣지 않습니다.'],
        ],
        policyExamples: ['oak → o*k', 'abcde → ab*de', 'player_12 → pla***_12', 'anti-talent → anti***lent'],
      },
      cold: {
        meterBody: (need) => `최근 1년 안의 테트라 리그 랭크 경기를 ${need}판 채우면 헤비 8개 챕터와 챕터별 AI 코멘트가 열립니다. 8개 중 6개는 상대와의 비교로 이루어집니다. 경기를 더 치른 뒤 전적 갱신(R)을 눌러 주세요.`,
        lockedTag: 'LOCKED · 8',
        lockedKr: '최근 1년 랭크 경기 10판이 쌓이면 열리는 챕터',
        toLight: '라이트 뷰로 보기',
        needOpp: '상대 비교 필요',
        needSample: '표본 부족',
        locked: 'LOCKED',
        policy: '경기 수가 적을 때 나온 수치는 몇 경기만 더 해도 크게 바뀔 수 있습니다.',
      },
    },
  },
}

const en: Strings = {
  disclaimer: 'Unofficial tool. Not affiliated with TETR.IO or osk.',
  officialApi: 'OFFICIAL API',
  home: 'Home',
  close: 'Close',

  infoBadge: 'What is this?',
  infoBadgeAria: 'Open the site description',
  infoTitle: 'What Finesse does',
  infoRegion: 'About this site',
  infoRows: [
    ['What it does', 'Enter a TETR.IO username and we pull the public record, then set your values next to those of the opponents you actually faced recently.'],
    ['Two depths', 'Light takes 30 seconds, Heavy takes 5 minutes. Pick by how long you want to spend reading.'],
    ['Opponents', 'Opponent names are always shown with the middle masked, and there are no links to their profiles or replays.'],
    ['Sign-in', 'Not required. Only public records are used.'],
  ],

  usernameLabel: 'USERNAME',
  placeholder: 'Enter a username',
  analyze: 'Analyze',
  viewSwitchAria: 'Analysis depth — off is Light, on is Heavy',
  view: { light: 'LIGHT', heavy: 'HEAVY' },
  errors: {
    empty: 'Enter a username.',
    tooShort: `Usernames are at least ${USERNAME_MIN} characters.`,
    tooLong: `Usernames are at most ${USERNAME_MAX} characters.`,
    invalidChars: 'Usernames can only contain letters, digits, _ and -.',
  },

  recentTitle: 'Recent',
  recentEmpty: 'Players you look up will show here',
  recentRemove: (name) => `Remove ${name} from recent searches`,
  keyAnalyze: 'Analyze',
  keyView: 'View',
  noLogin: 'No login, just a username',

  modeHint: 'Whichever you pick becomes the default report view',
  modeRows: {
    light: [
      ['30 sec', 'Time to read'],
      ['4 blocks', 'Win rate · TR trend · AI summary · 3 highlights'],
      ['2', 'Charts'],
    ],
    heavy: [
      ['5 min', 'Time to read'],
      ['8 chapters', 'All metrics · AI footnote per chapter'],
      ['7', 'Charts + frequent opponents'],
    ],
  },

  footerRights: 'All match data belongs to the original service. Opponent names are partially masked at collection time, and no profile or replay links are provided.',
  keyLight: 'Light',
  keyHeavy: 'Heavy',

  back: 'Search another player',

  loading: 'Loading match history…',
  retry: 'Try again',
  retryIn: (sec) => `Try again in ${sec}s`,
  statsErrors: {
    notFound: 'No player with that username. Check the spelling.',
    busy: 'Too many users right now. Please try again shortly.',
    unavailable: 'Temporarily unavailable. Please try again shortly.',
    network: 'Cannot reach the server. Check that the backend is running.',
    generic: 'Could not load the match history.',
  },
  report: {
    joined: (ago) => `Joined ${ago}`,
    playTime: (hours) => `${hours}h played`,
    games: (n) => `Last ${n} games`,
    updated: (ago) => `updated ${ago}`,
    officialApi: 'official API',
    xpProgress: (pct) => `${pct}% to next level`,
    viewTag: { light: 'Light · fixed metrics + 3 highlights', lightCold: 'Light · too few games', heavy: 'Heavy · 8 chapters' },
    viewSwitchAria: 'View — off is Light, on is Heavy',
    keyRefresh: 'Refresh',
    keyBack: 'Home',
    refresh: 'Refresh',
    refreshing: 'Loading match history…',
    refreshFailed: 'Could not refresh. Showing the previous result.',
    unranked: 'Unranked — RD is 100 or higher, so there is no tier to show. Analysis still runs.',
    friendCount: 'Players who have friended this user',
    profile: {
      season: 'Current season rank',
      trSub: 'Season ranked score',
      basis: 'BASIS',
      wl: (w, l) => `${w}W ${l}L`,
      rd: (rd) => `RD ±${rd}`,
      gamesSub: 'Recent games analyzed',
      apmSub: 'Attack per minute',
      ppsSub: 'Pieces per second',
      vsSub: 'Composite',
      note: (n) => `The change under each value compares your last ${n} games with the games before them (% change).`,
    },
    ai: {
      loading: 'Generating comment… stats are already shown',
      failed: 'Could not load the comment.',
      unavailable: 'Comments are temporarily unavailable.',
      hint: 'Hover a card to light up its evidence',
    },
    win: {
      title: 'Win / loss',
      recent: (n) => `Last ${n} games`,
      w: 'W',
      l: 'L',
      overall: (n, rate) => `Overall ${n} games ${rate}`,
      boardHint: 'Stacks bottom-up · filled cells are wins',
      empty: 'No ranked games in the last year.',
    },
    tr: {
      title: 'TR trend',
      games: (n) => `Last ${n} games`,
      high: 'Period high',
      low: 'Period low',
      change: 'Total change',
      perGame: 'Per game',
      missingTitle: 'No TR trend data came back.',
      missingBody: 'There are games on record, but the server sent no TR values. Try Refresh in a moment.',
    },
    cold: {
      title: 'Until analysis unlocks',
      leftUnit: 'to go',
      progress: (done, need) => `${done} of ${need} games played`,
      win: 'Win',
      loss: 'Loss',
      unknown: 'No result yet',
      winRate: 'Win rate so far',
      body: (need) => `Play ${need} Tetra League ranked games within the last year to unlock the AI summary and strength/weakness highlights. Then press Refresh (R).`,
    },
    hl: {
      caption: 'Metrics picked by AI · with the number behind each sentence',
      coldCaption: 'Not enough data',
      evidence: 'Evidence',
      coldTitle: 'Not enough recent match data to show highlights.',
      coldBody: (n) => `Analysis starts once 10 or more ranked games from the last year are on record. ${n} games so far.`,
      noneTitle: 'Could not generate highlights.',
      noneBody: 'The metrics the AI picked could not be tied to numbers in this record, so only the AI summary is shown.',
      failed: 'Could not load the highlights.',
    },
    heavy: {
      streamFailed: 'The chapter comment stream dropped. Chapters already received stay as they are.',
      foot: { loading: 'Generating comment · one call per chapter', failed: 'Could not write the comment for this chapter.', timeout: 'The comment for this chapter did not arrive in time.', warn: 'Caution' },
      evidence: 'Evidence',
      noData: 'There is no data to compute this chapter yet.',
      detail: { open: 'Details', close: 'Close', title: (c) => `${c} detail` },
      about: (name) => `About ${name}`,
      even: 'About the same',
      pillInfo: {
        app: {
          lead: 'How much more attack per piece you make than the opponent in each match · ▲ more, ▼ less',
          dir: { up: 'More attack per piece than opponents', down: 'Less attack per piece than opponents' },
          rows: [
            ['You', 'Your APP each match (APM ÷ (PPS × 60))'],
            ['Opponent', 'That match’s opponent’s APP'],
            ['Formula', 'You − opponent per match, averaged'],
            ['Range', 'Past year · up to 300, matches with APM 0 or PPS under 0.1 excluded'],
          ],
        },
        vsapm: {
          lead: 'How much more your defense-to-attack ratio is than the opponent in each match · ▲ more defensive, ▼ more offensive',
          dir: { up: 'Leans more on defense than opponents', down: 'Leans more on attack than opponents' },
          rows: [
            ['You', 'Your VS ÷ APM each match'],
            ['Opponent', 'That match’s opponent’s VS ÷ APM'],
            ['Formula', 'You − opponent per match, averaged'],
            ['Range', 'Past year · up to 300, matches with APM 0 or PPS under 0.1 excluded'],
          ],
        },
      },
      with: 'Also',
      chapters: {
        tr_trend: 'TR / ability trend',
        playstyle: 'Playstyle vs opponents',
        attack: 'Attack efficiency',
        defense: 'Defense · garbage',
        strength_split: 'Win rate by opponent strength',
        comeback_rate: 'Comeback performance',
        session_vs_slope: 'In-game condition',
        rivals: 'Frequent opponents',
      },
      trend: {
        legendTr: 'TR (actual value)',
        recentBand: (n) => `Last ${n} games (ΔTR basis)`,
        deltaInfo: (n, total) => ({
          lead: 'How far your recent average TR is above your average over all analyzed games · ▲ rising, ▼ falling',
          dir: { up: 'Recent TR is above your overall average', down: 'Recent TR is below your overall average' },
          rows: [
            ['Recent', `${n ? `Last ${n} games — ` : ''}30% of games with TR (min 3, max 30)`],
            ['All', `${total ? `${total} games — ` : ''}past year, up to 300, games without TR excluded`],
            ['Formula', 'Recent avg TR − overall avg TR'],
          ],
        }),
        avgOf: (n) => `Avg of ${n}g`,
        missing: 'No TR series was received, so only ΔTR is shown.',
        game: 'Game',
        tableNote: (n) => `All ${n} games · scroll inside the table`,
        aria: 'TR trend',
      },
      playstyle: { right: 'No absolute values · Δ only', metric: 'Metric', desc: 'Description', aria: 'Deviation vs opponents' },
      sub: { app: 'Attack per piece', wapp: 'Attack tendency', vsapm: 'Defense-to-attack ratio', cheese: 'Garbage clearing' },
      compare: { me: 'You', opp: 'Opp. avg', attackAria: 'Attack efficiency — you vs opponent average', defenseAria: 'Defense / garbage — you vs opponent average' },
      subInfo: {
        app: {
          lead: 'Attack lines sent per piece placed · higher means more attack from fewer pieces',
          dir: { up: 'More attack per piece than opponents', down: 'Less attack per piece than opponents' },
          rows: [
            ['Formula', 'APM ÷ (PPS × 60)'],
            ['Compared', 'Your value − that match’s opponent, averaged over matches'],
            ['Range', 'Past year · up to 300, matches with APM 0 or PPS under 0.1 excluded'],
          ],
        },
        wapp: {
          lead: 'Attack tendency · rises with aggressive play and drops sharply as Cheese Index (defensiveness) rises',
          dir: { up: 'More aggressive than opponents', down: 'Less aggressive than opponents' },
          rows: [
            ['Formula', 'APP reduced by Cheese Index'],
            ['Compared', 'Your value − that match’s opponent, averaged over matches'],
            ['Range', 'Past year · up to 300, matches with APM 0 or PPS under 0.1 excluded'],
          ],
        },
        vsapm: {
          lead: 'Defense-to-attack ratio · higher when you put more into defense (clearing garbage) than attack',
          dir: { up: 'Leans more on defense than opponents', down: 'Leans more on attack than opponents' },
          rows: [
            ['Formula', 'VS ÷ APM'],
            ['VS', '(attack lines sent + garbage lines cleared) ÷ seconds × 100'],
            ['Compared', 'Your value − that match’s opponent, averaged over matches'],
            ['Note', '+ leans defensive, − leans offensive — higher isn’t automatically better'],
          ],
        },
        cheese: {
          lead: 'Defensive tendency · higher with little attack and lots of efficient garbage clearing',
          dir: { up: 'More defensive than opponents', down: 'More offensive than opponents' },
          rows: [
            ['Formula', 'DS/P × 150 + (VS/APM − 2) × 50 + (0.6 − APP) × 125'],
            ['DS/P', 'Garbage lines cleared per piece'],
            ['Compared', 'Your value − that match’s opponent, averaged over matches'],
            ['Note', '+ more defensive than opponents, − more offensive — higher isn’t automatically better'],
          ],
        },
      },
      split: {
        right: 'TR gap vs opponent in fifths · the two ends',
        sub: 'Win rate vs strongest 20% − win rate vs weakest 20%',
        chartAria: 'Win rate by TR gap vs opponent',
        chartNote: 'Matches split into fifths by TR gap · weaker opponents on the left, stronger on the right',
        weak: 'weaker',
        strong: 'stronger',
        games: (n) => `${n}g`,
        table: ['Bracket', 'Games', 'Wins', 'Win rate'],
        info: {
          lead: 'How much your win rate drops against strong opponents compared with weak ones · closer to 0 is steadier',
          table: {
            caption: 'How the brackets are made',
            head: ['Bracket', 'Opponents'],
            rows: [
              ['Q1', 'Weakest 20%'],
              ['Q2–Q4', 'In between'],
              ['Q5', 'Strongest 20%'],
            ],
            foot: 'Games ranked by opponent TR − your TR (at match time), split into five equal groups',
          },
          rows: [
            ['Basis', 'Relative position within your own games, not fixed TR bands'],
            ['Range', 'Past year · up to 300, games with TR recorded'],
            ['Formula', 'Q5 win rate − Q1 win rate'],
            ['Excluded', 'Hidden when fewer than 5 games have TR'],
          ],
        },
      },
      comeback: {
        rateSub: 'Won after trailing',
        allowedSub: 'Lost after leading',
        of: (n, k) => `${k} of ${n}`,
        rateInfo: {
          lead: 'Matches won after falling well behind · higher is better',
          table: {
            caption: 'Comeback chance',
            head: ['Format', 'Rounds behind'],
            rows: [
              ['FT3', '2+'],
              ['FT5', '3+'],
              ['FT7', '4+'],
            ],
            foot: 'Before a round · half the wins needed, rounded up',
          },
          rows: [
            ['Range', 'Past year · up to 300'],
            ['Excluded', 'Unknown format · ended early'],
            ['Formula', 'Comeback wins ÷ chances'],
            ['Note', 'Few chances swing it a lot'],
          ],
        },
        allowedInfo: {
          lead: 'Matches lost after leading well · lower is better',
          table: {
            caption: 'Blown-lead chance',
            head: ['Format', 'Rounds ahead'],
            rows: [
              ['FT3', '2+'],
              ['FT5', '3+'],
              ['FT7', '4+'],
            ],
            foot: 'Before a round · half the wins needed, rounded up',
          },
          rows: [
            ['Range', 'Past year · up to 300'],
            ['Excluded', 'Unknown format · ended early'],
            ['Formula', 'Blown leads ÷ chances'],
            ['Note', 'Few chances swing it a lot'],
          ],
        },
        mine: 'Comeback rate',
        allowed: 'Comebacks allowed',
        mineMark: 'Comeback ▲',
        allowedMark: 'Allowed ▼',
        note: (max) => `Left: games turned around after trailing. Right: leads that were turned around (axis max ${max}%)`,
        aria: 'Comeback rate vs comebacks allowed',
        netInfo: {
          lead: 'How much higher your comeback rate is than your comebacks-allowed rate · ▲ higher, ▼ lower',
          dir: { up: 'You turn more games around than you let slip', down: 'You let more games slip than you turn around' },
          rows: [
            ['Comeback', 'Share of trailing matches you went on to win'],
            ['Allowed', 'Share of leading matches you went on to lose'],
            ['Trailing/leading', 'Score gap before a round — 2+ in FT3 · 3+ in FT5 · 4+ in FT7'],
            ['Formula', 'Comeback rate − comebacks allowed (%p)'],
            ['Range', 'Past year · up to 300, unknown format / early end excluded · hidden if either side has zero chances'],
          ],
        },
      },
      condition: {
        avgVs: 'Avg VS',
        ppsNorm: 'PPS (normalized)',
        first: 'R1 avg VS',
        last: (r) => `R${r} avg VS`,
        ppsChange: 'PPS change',
        round: 'Round',
        pps: 'PPS',
        samples: 'Rounds',
        head: (r, n) => (n === undefined ? `ROUND ${r}` : `ROUND ${r} · n=${n}`),
        perRound: 'VS change per round',
        slopeInfo: {
          lead: 'Whether VS climbs in later rounds of a match · ▲ rising, ▼ falling',
          dir: { up: 'VS rises as rounds go on', down: 'VS falls as rounds go on' },
          rows: [
            ['Early/late', 'Average VS across all matches for each round order (R1, R2 …)'],
            ['Formula', 'Slope of a line fitted to those averages = VS change per round'],
            ['Range', 'Every round in the past year (up to 300 matches), last round of early-ended matches excluded'],
            ['Note', 'Later rounds have fewer samples and swing more — round counts are in Details'],
          ],
        },
        rising: 'VS rises as rounds go on',
        falling: 'VS drops as rounds go on',
        flat: 'VS barely changes across rounds',
        aria: 'Average VS by round',
      },
      rivals: {
        right: (n) => `${n} rivals · 20 per page`,
        nemesis: 'Nemesis',
        edge: 'Edge',
        even: 'Close',
        repeat: 'Repeat rivals',
        repeatSub: 'Opponents met 5+ times',
        people: (n) => `${n}`,
        record: (w, l, p) => `${w}W ${l}L · ${p}%`,
        nemesisRule: 'Win rate ≤ 40%',
        edgeRule: '≥ 65%',
        evenRule: '45–55%',
        noLink: 'No links',
        noLinkB: 'Profiles · replays hidden',
        prev: 'Previous page',
        next: 'Next page',
        showing: (total, from, to) => `Showing ${from}–${to} of ${total} · most met first`,
        empty: 'No opponent on record yet.',
        policyTitle: 'How opponent data is handled',
        policy: [
          ['Length-preserving masking', 'Names are 3–16 characters of letters, digits, _ and -. ⌊length ÷ 3⌋ characters (at least 1) are hidden, starting at ⌊(length − hidden) ÷ 2⌋ — the middle is covered and the length is kept.'],
          ['No links', 'A separate requirement, not part of masking. Nothing on this screen leads to an opponent profile or replay. Table rows are not clickable.'],
          ['Applied to prompts too', 'The values sent to build AI comments use masked names as well. Original names never go into a prompt.'],
        ],
        policyExamples: ['oak → o*k', 'abcde → ab*de', 'player_12 → pla***_12', 'anti-talent → anti***lent'],
      },
      cold: {
        meterBody: (need) => `Play ${need} Tetra League ranked games within the last year to unlock the 8 heavy chapters and their AI comments. Six of the eight compare you against opponents. Then press Refresh (R).`,
        lockedTag: 'LOCKED · 8',
        lockedKr: 'Chapters that unlock at 10 ranked games in the last year',
        toLight: 'Open Light view',
        needOpp: 'Needs opponents',
        needSample: 'Too few games',
        locked: 'LOCKED',
        policy: 'With only a few games, the numbers can change a lot after just a few more.',
      },
    },
  },
}

export const STRINGS: Record<Lang, Strings> = { ko, en }
