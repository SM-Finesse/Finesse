// Finesse LLM Mock 서버 (프로토타입)
// 기능명세서 - 3.4/3.5절 스키마를 그대로 따르는 스텁 서버.
// 실제 추론 없이 고정 응답만 반환한다.
//
// [2026-09-22 갱신] scope=heavy(tr_trend_delta 챕터) 신규 구현, DEFAULT_STATS에서
// tr_trend_delta 제외. 근거: Finesse-데이터명세서 10절, tr_trend_delta_표본수_조건_설계.md.
// (meta.retry_count는 한때 추가했다가 같은 날 제거함 — 백엔드 응답에 들어갈 필드를
// 잘못 이 mock 서버에 넣었던 것으로 확인되어 되돌림. 아래 buildLightResponse 주석 참고.)
//
// 실행: node server.js
// 테스트 (light):
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=light"
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=light&mode=delay"
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=light&mode=error"
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=light&mode=excess"
// 테스트 (heavy, 신규):
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=heavy&totalGames=45"
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=heavy&totalGames=60"   (경계값 — N=20)
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=heavy&totalGames=8"    (10판 미만 — 콜드스타트, 값 미노출)
//
// stats 파라미터: 이 유저에게 "실제로 존재하는" 후보 지표 목록을 쉼표로 전달하면,
// 그 안에서만 하이라이트를 고른다. 안 주면 기존 기본값을 그대로 씀.
// [2026-09-22] tr_trend_delta는 라이트 뷰 하이라이트 후보 풀에서 제외 확정
// (데이터 명세서 10절) — 기본값에서 빠지고 session_vs_slope로 대체.
// 예 — delta_comeback이 표본 부족(분모 0)으로 null이라 후보에서 빠진 유저를 흉내내기:
//   curl "http://localhost:9000/api/v1/comment/testuser?scope=light&stats=delta_plonk,strength_split,session_vs_slope"

const http = require("http");
const { URL } = require("url");

const PORT = process.env.PORT || 9000;

// 알려진 후보 지표별 코멘트 문장 (기능명세서 3.4절 후보 풀 기준)
// tr_trend_delta는 라이트 뷰 후보에서 빠졌으므로 이 표에서 제거하고, 헤비 뷰 전용
// 문장 생성 로직(sentenceForTrTrendDelta)으로 따로 분리했다.
const SENTENCES = {
  delta_opener: "상대 대비 Opener 성향이 뚜렷합니다.",
  delta_plonk: "상대 대비 Plonk 성향이 뚜렷하게 강한 편입니다.",
  delta_stride: "상대 대비 Stride 성향이 낮은 편입니다.",
  delta_inf_ds: "상대 대비 Inf DS 성향이 강한 편입니다.",
  delta_app: "상대 대비 공격 효율(APP)이 높은 편입니다.",
  delta_weighted_app: "상대 대비 가중 공격 효율이 높은 편입니다.",
  delta_vs_apm: "상대 대비 수비 지표(VS/APM)가 안정적입니다.",
  delta_cheese_index: "상대 대비 가비지 처리 패턴이 특징적입니다.",
  strength_split: "본인보다 강한 상대와의 승률이 눈에 띄게 낮습니다.",
  // [2026-10-01 정정] comeback_rate → delta_comeback. "하이라이트 지표 설계" 문서
  // 2026-09-30 결정(9절)으로 라이트 뷰 하이라이트 후보 키가 교체됨
  // (delta_comeback = comeback_rate − comeback_rate_against). 문장도 절대값 서술
  // ("비율이 평균보다 높습니다")에서 경향성 서술로 바꿈 — light는 델타값 하나만
  // 받으므로 "몇 판 중 몇 번" 식 구체적 수치는 지어내지 않는다(같은 문서 규칙 8).
  delta_comeback: "상대 대비 불리한 상황을 뒤집는 경향이, 유리한 상황에서 뒤집히는 경향보다 강한 편입니다.",
  session_vs_slope: "경기가 진행될수록 컨디션이 상승하는 경향이 있습니다.",
};

// stats 파라미터가 없을 때 쓰는 기존 기본값.
// [2026-09-22] tr_trend_delta 제외 확정에 따라 session_vs_slope로 교체.
// [2026-10-01] comeback_rate → delta_comeback (위 SENTENCES 주석 참고).
const DEFAULT_STATS = ["delta_plonk", "delta_comeback", "session_vs_slope"];

function sentenceFor(stat) {
  return SENTENCES[stat] || `${stat} 관련 특징이 관찰됩니다.`;
}

function buildHighlights(stats) {
  return stats.map((stat) => ({ stat, sentence: sentenceFor(stat) }));
}

// ---------------------------------------------------------------------------
// [2026-09-22 신규] tr_trend_delta 헤비 뷰 처리
// 근거: 데이터 명세서 10절 / tr_trend_delta_표본수_조건_설계.md
// tr_trend_delta = 최근 N판 평균 지표 - 전체 평균 지표. N은 전체 판수 구간별
// 계단식 값(고정 10 아님). 경계값(정확히 30/60/100/150판)은 상위 구간 적용 —
// 아래처럼 ">=" 비교만 쓰면 경계값이 자연히 상위 구간으로 떨어지므로 별도
// 분기 없이 규칙이 성립한다.
// ---------------------------------------------------------------------------
function resolveRecentWindow(totalGames) {
  if (totalGames >= 150) return 30;
  if (totalGames >= 100) return 25;
  if (totalGames >= 60) return 20;
  if (totalGames >= 30) return 15;
  return 10; // 10~29판 구간. 10판 미만은 호출측(6.1절 콜드스타트 규칙)에서 애초에 heavy를 호출하지 않아야 함.
}

// 결정론적 난수 (tetrio-mock과 동일한 mulberry32 — 같은 유저면 항상 같은 값이 나오게)
function mulberry32(seed) {
  return function () {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function buildTrTrendDelta(username, totalGames) {
  // 10판 미만: 콜드스타트 규칙(6.1절) — 값 자체를 노출하지 않음
  if (totalGames < 10) {
    return { n: null, value: null, exposed: false, reason: "표본 부족 (총 10판 미만) — 6.1절 콜드스타트 규칙" };
  }

  const n = resolveRecentWindow(totalGames);
  const rng = mulberry32([...username].reduce((a, c) => a + c.charCodeAt(0), 0) + n);
  const value = +((rng() - 0.5) * 120).toFixed(1); // 데모용 임의 델타 (실제 계산은 백엔드/데이터 엔지니어링 담당)
  const direction = value >= 0 ? "상승" : "하락";
  return {
    n,
    value,
    exposed: true,
    sentence: `최근 ${n}판 기준 TR이 이전 대비 ${direction}하는 흐름을 보이고 있습니다.`,
  };
}

// QA 데모용 예외 응답 모드 (실제 채택 시 mock서버제안 문서 2.1절 기준으로 확장 논의 필요)
function buildLightResponse(mode, requestedStats) {
  // 실제 LLM은 입력에 없는 지표를 하이라이트로 고를 수 없으므로, 여기도 주어진 후보 안에서만 고른다.
  const pool = requestedStats && requestedStats.length ? requestedStats : DEFAULT_STATS;
  const light_summary =
    "최근 대전에서 상대 대비 다소 공격적인 플레이 스타일을 보이고 있습니다. 전반적인 TR 흐름은 상승세입니다.";

  // [2026-09-22 정정] meta.retry_count는 여기 있으면 안 된다 — 삭제함.
  // retry_count는 "백엔드가 LLM/Mock 응답 형식이 이상해서 몇 번 재요청했는지"를
  // 세는 값이라, 세는 주체도 저장 위치도 백엔드다. mock-llm은 상태가 없는 스텁이라
  // 자신이 몇 번 재시도당했는지 알 방법 자체가 없다. 백엔드가 자기 응답에 넣어야
  // 할 필드를 mock 응답에 넣는 건 스키마 위치가 틀린 것이므로 되돌린다.

  switch (mode) {
    case "excess":
      // 3개 초과 응답 - 기능명세서 3.3절 "앞 3개만 사용" 검증용 (여분 항목은 의도적으로 후보 밖 값 사용)
      return {
        light_summary,
        highlights: [
          ...buildHighlights(pool.slice(0, 3)),
          { stat: "session_vs_slope", sentence: "여분의 4번째 하이라이트 (형식 오류 시뮬레이션)." },
        ],
      };
    case "few":
      // 3개 미만 응답 - 재요청 로직 검증용
      return { light_summary, highlights: buildHighlights(pool.slice(0, 1)) };
    default:
      return { light_summary, highlights: buildHighlights(pool.slice(0, 3)) };
  }
}

function buildHeavyResponse(username, totalGames) {
  // [2026-09-22] heavy는 이번에 tr_trend_delta 챕터만 우선 구현.
  // 나머지 7개 헤비 뷰 챕터(플레이스타일/공격효율/수비/상대강도별승률/역전승/
  // 컨디션변화/라이벌)는 아직 미구현 — 다음 단계로 남겨둠.
  // meta.retry_count는 위 buildLightResponse와 같은 이유로 넣지 않는다(백엔드 소관).
  const trTrendDelta = buildTrTrendDelta(username, totalGames);
  return {
    heavy: {
      tr_trend_delta: trTrendDelta,
    },
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok" }));
    return;
  }

  const match = url.pathname.match(/^\/api\/v1\/comment\/([^/]+)$/);
  if (req.method === "GET" && match) {
    const username = match[1];
    const scope = url.searchParams.get("scope") || "light";
    const mode = url.searchParams.get("mode") || "normal";
    const statsParam = url.searchParams.get("stats");
    const requestedStats = statsParam
      ? statsParam.split(",").map((s) => s.trim()).filter(Boolean)
      : null;

    if (mode === "error") {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Mock: LLM 서버 내부 오류 시뮬레이션" }));
      return;
    }

    const respond = () => {
      if (scope === "heavy") {
        // totalGames: 실제로는 백엔드가 tetrio-mock의 /matches total 값을 넘겨줘야 함.
        // 안 주면 데모용 기본값 60(=testuser 픽스처 판수, 60/99 경계값 테스트 가능) 사용.
        const totalGamesParam = url.searchParams.get("totalGames");
        const totalGames = totalGamesParam !== null ? parseInt(totalGamesParam, 10) : 60;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(buildHeavyResponse(username, totalGames), null, 2));
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(buildLightResponse(mode, requestedStats), null, 2));
    };

    if (mode === "delay") {
      setTimeout(respond, 3000); // 3초 지연 - 프론트 로딩 스켈레톤 테스트용
    } else {
      respond();
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ error: "not found" }));
});

server.listen(PORT, () => {
  console.log(`[mock-llm] listening on port ${PORT}`);
});
