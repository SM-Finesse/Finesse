import type { HeavyChapterId } from '../api/types'
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
    chapters: Record<HeavyChapterId, string>
    trend: {
      legendTr: string
      recent: (n: number) => string
      avgOf: (n: number) => string
      gap: string
      missing: string
      game: string
      tableNote: (n: number) => string
      aria: string
    }
    playstyle: { right: string; metric: string; desc: string; aria: string }
    sub: Record<'app' | 'wapp' | 'vsapm' | 'cheese', string>
    split: { right: string; sub: string }
    comeback: {
      rateSub: string
      allowedSub: string
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
      perRound: string
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
    unranked: '랭크 없음',
    friendCount: '이 유저를 친구로 추가한 플레이어 수',
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
      body: (need) => `테트라 리그 랭크 경기를 ${need}판 채우면 AI 총평과 강점·약점 하이라이트가 열립니다. 경기를 더 치른 뒤 전적 갱신(R)을 눌러 주세요.`,
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
      streamFailed: '챕터 코멘트 연결이 끊겼습니다. 받은 챕터는 그대로 둡니다.',
      foot: { loading: '코멘트를 생성하는 중입니다 · 챕터별 개별 호출', failed: '이 챕터의 코멘트를 만들지 못했습니다.', timeout: '시간 안에 이 챕터의 코멘트를 받지 못했습니다.', warn: '주의' },
      evidence: '근거',
      noData: '이 챕터를 계산할 데이터가 아직 없습니다.',
      detail: { open: '자세히 보기', close: '닫기', title: (c) => `${c} 상세` },
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
        recent: (n) => `Recent ${n}g`,
        avgOf: (n) => `Avg of ${n}g`,
        gap: 'Gap',
        missing: 'TR 추이 데이터를 받지 못해 ΔTR만 보여줍니다.',
        game: '경기',
        tableNote: (n) => `${n}경기 전부 · 표 안에서 스크롤`,
        aria: 'TR 추이',
      },
      playstyle: { right: '절대값 비노출 · Δ만 표시', metric: 'Metric', desc: '설명', aria: '상대 대비 편차' },
      sub: { app: '블록당 공격량', wapp: '가중 공격 효율', vsapm: '수비 여력', cheese: '가비지 처리' },
      split: { right: '상대 TR 5등분 · 양 끝 구간', sub: '가장 강한 상대 20% 승률 − 가장 약한 상대 20% 승률' },
      comeback: {
        rateSub: '2판 이상 뒤진 경기를 뒤집은 비율',
        allowedSub: '2판 이상 앞선 경기를 뒤집힌 비율',
        mine: '내 역전승률',
        allowed: '역전 허용률',
        mineMark: '역전 성공 ▲',
        allowedMark: '역전 허용 ▼',
        note: (max) => `왼쪽은 뒤진 경기를 뒤집은 비율, 오른쪽은 앞선 경기를 뒤집힌 비율 (축 최대 ${max}%)`,
        aria: '역전승률과 역전 허용률 비교',
      },
      condition: {
        avgVs: '평균 VS',
        ppsNorm: 'PPS (정규화)',
        first: 'R1 평균 VS',
        last: (r) => `R${r} 평균 VS`,
        ppsChange: 'PPS 변화',
        round: 'Round',
        pps: 'PPS',
        perRound: '라운드당 VS 변화',
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
          ['길이 보존 마스킹', '닉네임은 3~16자, 영문·숫자·_만 쓸 수 있습니다. 가릴 글자 수는 ⌊길이 ÷ 3⌋(최소 1자), 시작 위치는 ⌊(길이 − 가릴 글자 수) ÷ 2⌋ — 가운데를 덮되 전체 길이는 원본 그대로 둡니다.'],
          ['링크 비노출', '마스킹의 부속이 아닌 별도 요건입니다. 이 화면 어디에서도 상대의 프로필이나 리플레이로 바로 갈 수 없습니다. 표의 행은 클릭 대상이 아닙니다.'],
          ['프롬프트에도 적용', 'AI 코멘트를 만들 때 넘기는 값도 마스킹된 닉네임입니다. 원본 닉네임은 프롬프트에 넣지 않습니다.'],
        ],
        policyExamples: ['oak → o*k', 'abcde → ab*de', 'player_12 → pla***_12'],
      },
      cold: {
        meterBody: (need) => `테트라 리그 랭크 경기를 ${need}판 채우면 헤비 8개 챕터와 챕터별 AI 코멘트가 열립니다. 8개 중 6개는 상대와의 비교로 이루어집니다. 경기를 더 치른 뒤 전적 갱신(R)을 눌러 주세요.`,
        lockedTag: 'LOCKED · 8',
        lockedKr: '10경기가 쌓이면 열리는 챕터',
        toLight: '라이트 뷰로 보기',
        needOpp: '상대 비교 필요',
        needSample: '표본 부족',
        locked: 'LOCKED',
        policy: '표본이 모자란 상태에서 뽑은 수치는 다음 몇 경기에 그대로 뒤집힙니다. 값을 보여주고 주의 문구를 붙이는 대신, 아예 열지 않습니다.',
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
    unranked: 'Unranked',
    friendCount: 'Players who have friended this user',
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
      body: (need) => `Play ${need} Tetra League ranked games to unlock the AI summary and strength/weakness highlights. Then press Refresh (R).`,
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
      streamFailed: 'The chapter comment stream dropped. Chapters already received stay as they are.',
      foot: { loading: 'Generating comment · one call per chapter', failed: 'Could not write the comment for this chapter.', timeout: 'The comment for this chapter did not arrive in time.', warn: 'Caution' },
      evidence: 'Evidence',
      noData: 'There is no data to compute this chapter yet.',
      detail: { open: 'Details', close: 'Close', title: (c) => `${c} detail` },
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
        recent: (n) => `Recent ${n}g`,
        avgOf: (n) => `Avg of ${n}g`,
        gap: 'Gap',
        missing: 'No TR series was received, so only ΔTR is shown.',
        game: 'Game',
        tableNote: (n) => `All ${n} games · scroll inside the table`,
        aria: 'TR trend',
      },
      playstyle: { right: 'No absolute values · Δ only', metric: 'Metric', desc: 'Description', aria: 'Deviation vs opponents' },
      sub: { app: 'Attack per piece', wapp: 'Weighted attack', vsapm: 'Defensive headroom', cheese: 'Garbage clearing' },
      split: { right: 'Opponent TR in fifths · the two ends', sub: 'Win rate vs strongest 20% − win rate vs weakest 20%' },
      comeback: {
        rateSub: 'Games won after trailing by 2+ rounds',
        allowedSub: 'Games lost after leading by 2+ rounds',
        mine: 'Comeback rate',
        allowed: 'Comebacks allowed',
        mineMark: 'Comeback ▲',
        allowedMark: 'Allowed ▼',
        note: (max) => `Left: games turned around after trailing. Right: leads that were turned around (axis max ${max}%)`,
        aria: 'Comeback rate vs comebacks allowed',
      },
      condition: {
        avgVs: 'Avg VS',
        ppsNorm: 'PPS (normalized)',
        first: 'R1 avg VS',
        last: (r) => `R${r} avg VS`,
        ppsChange: 'PPS change',
        round: 'Round',
        pps: 'PPS',
        perRound: 'VS change per round',
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
          ['Length-preserving masking', 'Names are 3–16 characters of letters, digits and _. ⌊length ÷ 3⌋ characters (at least 1) are hidden, starting at ⌊(length − hidden) ÷ 2⌋ — the middle is covered and the length is kept.'],
          ['No links', 'A separate requirement, not part of masking. Nothing on this screen leads to an opponent profile or replay. Table rows are not clickable.'],
          ['Applied to prompts too', 'The values sent to build AI comments use masked names as well. Original names never go into a prompt.'],
        ],
        policyExamples: ['oak → o*k', 'abcde → ab*de', 'player_12 → pla***_12'],
      },
      cold: {
        meterBody: (need) => `Play ${need} Tetra League ranked games to unlock the 8 heavy chapters and their AI comments. Six of the eight compare you against opponents. Then press Refresh (R).`,
        lockedTag: 'LOCKED · 8',
        lockedKr: 'Chapters that unlock at 10 games',
        toLight: 'Open Light view',
        needOpp: 'Needs opponents',
        needSample: 'Too few games',
        locked: 'LOCKED',
        policy: 'Numbers pulled from too small a sample flip over within the next few games. Rather than showing them with a warning, the chapters stay closed.',
      },
    },
  },
}

export const STRINGS: Record<Lang, Strings> = { ko, en }
