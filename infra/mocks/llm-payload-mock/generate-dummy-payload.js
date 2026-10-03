// Finesse LLM Payload Mock — 파인튜닝 완료 전까지만 쓰는 임시 스크립트.
// LLM/AI 담당이 실제 Qwen 모델에 넣어볼 입력값(prompt 조립 재료)이 필요한데,
// 백엔드 계산 파이프라인(Fancy/Delta/Highlight Calculator)이 아직 없어도
// 형식만 맞는 더미를 뽑아 쓸 수 있게 하는 스크립트다.
//
// 기존 mock-llm(llm-mock-1/2)과는 반대 방향이다:
//   mock-llm    = LLM이 "낼 법한 출력(코멘트)"을 흉내냄 — 백엔드/프론트가 씀
//   이 스크립트 = LLM이 "받을 입력(payload)"을 흉내냄 — LLM/AI 담당이 씀
//
// ---------------------------------------------------------------------------
// [2026-10-01 전면 개정] 근거 문서 교체: "Finesse — 하이라이트 지표 설계"
// (2026-09-30 작성, 상태: PM 검수 대기 — 아직 최종 확정은 아님, 이후 바뀔 수 있음)
//
// 이전 버전은 "TETR.IO 분석 데이터 파이프라인 모듈 설계서 v1.4" 17~18절을 근거로
// styleSignals/highlightCandidates(9종 composite 카테고리, summaryHint enum) 구조를
// 만들었으나, 2026-10-01 재확인 결과 그 9종 체계는 다른 어떤 정본 문서에도 없고
// (v1.0/v1.1/v2.3/이번 하이라이트 지표 설계서 전부 불일치), 실제로는 처음부터
// 틀린 근거였을 가능성이 높다. "하이라이트 지표 설계" 문서가 데이터명세서·
// 기능명세서·파이프라인 설계서 v1.6·프로토타입 4곳의 불일치를 정리한 최신 통합본이므로
// 이번 개정은 이 문서를 그대로 따른다 — 후보 11개, raw delta 값을 LLM에 직접 전달
// (구버전의 "원시 delta 금지" 규칙은 이 문서에서 폐기됨), summaryHint 필드 없음.
//
// 11개 후보 (하이라이트 지표 설계 2장):
//   playstyle_relative: delta_opener, delta_plonk, delta_stride, delta_inf_ds
//   attack:             delta_app, delta_weighted_app
//   defense:            delta_vs_apm, delta_cheese_index
//   strength_split, delta_comeback(2026-09-30 신설 — comeback_rate 대체), session_vs_slope
//
// 서버로 안 만든 이유: LLM/AI 담당이 원격 PC에서 혼자 쓰는 용도라
// 포트 열어두는 상시 서버보다, 필요할 때 한 번 실행해서 JSON 받는 스크립트가 더 가볍다.
//
// 실행 (로컬):
//   node generate-dummy-payload.js testuser
//   node generate-dummy-payload.js testuser --scope=heavy --totalGames=45
//
// 실행 (Docker, 레포 통합 관리 — docker compose up 만으로는 안 뜸, profile 명시 필요):
//   docker compose --profile llm-payload run --rm llm-payload-mock testuser --scope=heavy

// ---------------------------------------------------------------------------
// 결정론적 난수 — mock-llm/server.js, tetrio-mock/server.js와 동일한 mulberry32.
// 같은 유저명이면 항상 같은 payload가 나와야 LLM/AI 담당이 프롬프트를 비교/디버깅하기 쉽다.
// ---------------------------------------------------------------------------
function mulberry32(seed) {
  return function () {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function seedFrom(username, salt) {
  return [...username].reduce((a, c) => a + c.charCodeAt(0), 0) + salt;
}

function round2(n) {
  return Math.round(n * 100) / 100;
}

// 양수/음수 양쪽으로 고르게 퍼지는 더미 delta 값. spread는 대략적인 값의 범위.
function randDelta(rng, spread) {
  return round2((rng() - 0.5) * spread);
}

// ---------------------------------------------------------------------------
// 11개 후보 — 하이라이트 지표 설계 2장 표 그대로. null 조건은 해당 절 "null/제외 조건" 열 요약.
// ---------------------------------------------------------------------------

// 플레이스타일 4종(1~4번) — 원값 직접 차감이 아니라 StatrankCurve 퍼센타일 변환 후 차감.
// 정규화 가능한 매치가 전체 300판의 50% 미만이면 그 필드만 null.
// 더미에서는 이 "정규화 표본 부족" 상황을 ~15% 확률로 흉내낸다(실제 비율 계산은 하지 않음).
const PLAYSTYLE_NULL_CHANCE = 0.15;
function buildPlaystyleRelative(rng) {
  const out = {};
  for (const key of ["delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds"]) {
    out[key] = rng() < PLAYSTYLE_NULL_CHANCE ? null : randDelta(rng, 0.6);
  }
  return out;
}

// 공격 효율(5~6번) — APP/Weighted APP 평균 차감. 매치 단위 방어 로직(apm=0, pps<0.1 제외,
// tan 발산 폴백)은 집계 자체를 막지 않으므로, 게이팅(10판 이상)을 통과했다면 거의 항상 값이 있다.
function buildAttack(rng) {
  return {
    delta_app: randDelta(rng, 0.3),
    delta_weighted_app: randDelta(rng, 4),
  };
}

// 수비·가비지 처리(7~8번) — 위와 동일한 이유로 거의 항상 값이 있다.
function buildDefense(rng) {
  return {
    delta_vs_apm: randDelta(rng, 0.6),
    delta_cheese_index: randDelta(rng, 10),
  };
}

// 상대 강도별 승률(9번) — Quintile 최소 표본 조건 폐기됨(총 10판 이상이면 항상 계산).
function buildStrengthSplit(rng) {
  return randDelta(rng, 0.4);
}

// 역전승 퍼포먼스(10번) — 2026-09-30 신설: comeback_rate → delta_comeback
// (= comeback_rate − comeback_rate_against). 둘 중 하나라도 분모 0(기회 자체가 없음)이면
// null. 더미에서는 ~12% 확률로 그 상황을 흉내낸다.
const DELTA_COMEBACK_NULL_CHANCE = 0.12;
function buildDeltaComeback(rng) {
  return rng() < DELTA_COMEBACK_NULL_CHANCE ? null : randDelta(rng, 0.5);
}

// 경기 내 컨디션 변화(11번) — session_vs_slope. 매치 제외 없음(분모 0 등은 0.0 폴백이라
// null이 아니라 0에 가까운 값으로 수렴할 뿐 — 그래서 여기도 null을 만들지 않는다).
function buildSessionVsSlope(rng) {
  return randDelta(rng, 2);
}

function buildDeltaMetrics(rng, scope) {
  const delta_metrics = {
    playstyle_relative: buildPlaystyleRelative(rng),
    attack: buildAttack(rng),
    defense: buildDefense(rng),
    strength_split: buildStrengthSplit(rng),
    delta_comeback: buildDeltaComeback(rng),
    session_vs_slope: buildSessionVsSlope(rng),
  };

  if (scope === "heavy") {
    // [문서 범위 밖 — 이 스크립트의 확장] "하이라이트 지표 설계" 문서는 scope=light 스키마만
    // 다룬다. heavy 뷰는 챕터별 개별 요청이라 이 11개를 한 번에 묶어 보내는 구조가 아니지만,
    // 이 스크립트는 편의상 heavy에서 tr_trend_delta(라이트 11개 후보에서는 제외되고 heavy
    // "TR/능력치 추이" 챕터 전용인 값, 1절)를 같은 객체에 얹어서 반환한다.
    delta_metrics.tr_trend_delta = randDelta(rng, 300);
  }

  return delta_metrics;
}

function buildFixedMetrics(rng) {
  const win_rate = round2(0.3 + rng() * 0.4);
  const base = 18000 + Math.floor(rng() * 4000);
  const tr_trend = [0, 1, 2, 3].map((i) => base + Math.round((rng() - 0.3) * 150 * (i + 1)));
  return { win_rate, tr_trend };
}

function generate(username, scope, totalGames) {
  const rng = mulberry32(seedFrom(username, scope === "heavy" ? 900 : 100));

  // 5절 표본 부족 게이팅(2026-09-30, 2단계):
  //   1단계 gamesplayed < 10 → 즉시 종료
  //   2단계 1단계 통과했어도 수집범위(최근 300판 + 최근 1년 이중조건) 내 매치 < 10 → 동일 처리
  // [알려진 단순화] 이 스크립트는 --totalGames 하나로만 게이팅을 흉내내므로 1단계만 시뮬레이션한다.
  // 2단계(수집범위 내 매치 수)는 실제 수집 로직이 없어 별도로 재현하지 않는다.
  if (totalGames < 10) {
    return {
      llmRequest: null,
      note: "totalGames < 10 — 표본 부족 게이팅 1단계, LLM 호출 없음 (2단계 수집범위 조건은 이 스크립트가 시뮬레이션하지 않음)",
    };
  }

  return {
    llmRequest: {
      fixed_metrics: buildFixedMetrics(rng),
      delta_metrics: buildDeltaMetrics(rng, scope),
    },
  };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const [username, ...rest] = argv;
  const opts = { scope: "light", totalGames: 60 };
  for (const arg of rest) {
    const [k, v] = arg.replace(/^--/, "").split("=");
    if (k === "scope") opts.scope = v;
    if (k === "totalGames") opts.totalGames = parseInt(v, 10);
  }
  return { username, opts };
}

const { username, opts } = parseArgs(process.argv.slice(2));

if (!username) {
  console.error("사용법: node generate-dummy-payload.js <username> [--scope=light|heavy] [--totalGames=N]");
  process.exit(1);
}

console.log(JSON.stringify(generate(username, opts.scope, opts.totalGames), null, 2));
