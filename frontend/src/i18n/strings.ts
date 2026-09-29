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

  examples: string
  exampleCold: string
  keyAnalyze: string
  keyView: string
  noLogin: string

  modeHint: string
  modeRows: Record<View, readonly Row[]>

  footerRights: string
  keyLight: string
  keyHeavy: string

  reportTitle: (name: string) => string
  reportBody: (view: string) => string
  back: string
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
    invalidChars: '유저명에는 영문 · 숫자 · _ 만 쓸 수 있습니다.',
  },

  examples: '예시로 열어보기',
  exampleCold: 'NewPlayer · 7경기',
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

  reportTitle: (name) => `${name} 리포트를 준비합니다`,
  reportBody: (view) => `${view} 뷰로 요청했습니다. 결과 화면은 다음 단계에서 연결됩니다.`,
  back: '다른 유저 검색',
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
    invalidChars: 'Usernames can only contain letters, digits and _.',
  },

  examples: 'Try an example',
  exampleCold: 'NewPlayer · 7 games',
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

  reportTitle: (name) => `Preparing the report for ${name}`,
  reportBody: (view) => `Requested in ${view} view. The report screen is wired up in the next step.`,
  back: 'Search another player',
}

export const STRINGS: Record<Lang, Strings> = { ko, en }
