import type { Lang, View } from '../types'
import type { UsernameError } from '../lib/username'
import { USERNAME_MAX, USERNAME_MIN } from '../lib/username'

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
  statsErrors: Record<'notFound' | 'unavailable' | 'network' | 'generic', string>
  report: ReportStrings
}

export interface ReportStrings {
  joined: (ago: string) => string
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
  profile: {
    season: string
    trSub: string
    basis: string
    trDelta: string
    wl: (w: number, l: number) => string
    rd: (rd: string) => string
    gamesSub: string
    apmSub: string
    ppsSub: string
    vsSub: string
    note: string
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
    emptyTitle: string
    emptyBody: (n: number) => string
    missingTitle: string
    missingBody: string
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
  heavy: { title: string; body: string; toLight: string }
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
  statsErrors: {
    notFound: '해당 유저를 찾을 수 없습니다. 유저명을 확인해 주세요.',
    unavailable: '일시적으로 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.',
    network: '서버에 연결할 수 없습니다. 백엔드가 켜져 있는지 확인해 주세요.',
    generic: '전적을 불러오지 못했습니다.',
  },
  report: {
    joined: (ago) => `가입 ${ago}`,
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
    unranked: '랭크 없음',
    profile: {
      season: '현재 시즌 랭크',
      trSub: '시즌 대전 점수',
      basis: '비교 기준',
      trDelta: '최근 10경기',
      wl: (w, l) => `${w}승 ${l}패`,
      rd: (rd) => `편차 ±${rd}`,
      gamesSub: '분석한 최근 경기',
      apmSub: '분당 공격',
      ppsSub: '초당 블록',
      vsSub: '종합 지표',
      note: 'TR 옆 증감은 최근 10경기 평균과 그 이전 10경기 평균의 차이입니다.',
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
      emptyTitle: '추이를 그릴 만큼 기록이 없습니다.',
      emptyBody: (n) => `10경기가 쌓이면 경기마다 TR 변화를 그립니다. 현재 ${n}경기.`,
      missingTitle: 'TR 추이 데이터를 받지 못했습니다.',
      missingBody: '경기 기록은 있지만 서버가 TR 변화 값을 보내지 않았습니다. 잠시 뒤 전적 갱신을 눌러 보세요.',
    },
    hl: {
      caption: 'AI가 고른 지표 · 문장마다 근거가 된 수치',
      coldCaption: '데이터 부족',
      evidence: '근거',
      coldTitle: '최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.',
      coldBody: (n) => `10경기 이상 기록이 쌓이면 상대 대비 강점·약점 분석을 시작합니다. 현재 ${n}경기 기록됨.`,
      noneTitle: '근거와 이어지는 하이라이트가 없습니다.',
      noneBody: 'AI가 고른 지표가 이번 전적에서 계산되지 않아 표시하지 않았습니다.',
      failed: '하이라이트를 불러오지 못했습니다.',
    },
    heavy: {
      title: '헤비 뷰는 다음 단계에서 연결됩니다.',
      body: '8개 챕터 화면은 준비 중입니다. 지금은 라이트 뷰에서 결과를 볼 수 있습니다.',
      toLight: '라이트 뷰로 보기',
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
  statsErrors: {
    notFound: 'No player with that username. Check the spelling.',
    unavailable: 'Temporarily unavailable. Please try again shortly.',
    network: 'Cannot reach the server. Check that the backend is running.',
    generic: 'Could not load the match history.',
  },
  report: {
    joined: (ago) => `Joined ${ago}`,
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
    unranked: 'Unranked',
    profile: {
      season: 'Current season rank',
      trSub: 'Season ranked score',
      basis: 'BASIS',
      trDelta: 'last 10 games',
      wl: (w, l) => `${w}W ${l}L`,
      rd: (rd) => `RD ±${rd}`,
      gamesSub: 'Recent games analyzed',
      apmSub: 'Attack per minute',
      ppsSub: 'Pieces per second',
      vsSub: 'Composite',
      note: 'The change next to TR is the average of your last 10 games minus the 10 before that.',
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
      emptyTitle: 'Not enough history to plot a trend.',
      emptyBody: (n) => `The TR line starts at 10 games. ${n} games so far.`,
      missingTitle: 'No TR trend data came back.',
      missingBody: 'There are games on record, but the server sent no TR values. Try Refresh in a moment.',
    },
    hl: {
      caption: 'Metrics picked by AI · with the number behind each sentence',
      coldCaption: 'Not enough data',
      evidence: 'Evidence',
      coldTitle: 'Not enough recent match data to show highlights.',
      coldBody: (n) => `Analysis starts once 10 or more games are on record. ${n} games so far.`,
      noneTitle: 'No highlight could be tied to a number.',
      noneBody: 'The metrics the AI picked were not computed for this record, so they are hidden.',
      failed: 'Could not load the highlights.',
    },
    heavy: {
      title: 'The Heavy view is wired up in the next step.',
      body: 'The 8-chapter screen is still in progress. For now the results are in the Light view.',
      toLight: 'Open Light view',
    },
  },
}

export const STRINGS: Record<Lang, Strings> = { ko, en }
