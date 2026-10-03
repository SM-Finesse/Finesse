// Finesse TETR.IO API Mock 서버 (프로토타입, 구축가이드 2부 — 2.2절)
// 실제 TETR.IO 공식 API를 대신할 고정 fixture 서버. 유저 존재 확인 + 매치 히스토리(상대 스탯 포함)를 흉내낸다.
// 주의: 실제 TETR.IO API의 정확한 필드명/경로는 아직 대조 확인 전이라(백엔드 API 명세서 10절 미정 사항),
// 이 응답 형태는 "백엔드가 짤 파싱 로직을 미리 연습할 수 있는" 수준의 근사치다. 실제 스펙 확정 시 갱신 필요.
//
// 실행: node server.js
// 테스트: curl "http://localhost:9000/users/testuser"
//        curl "http://localhost:9000/users/testuser/matches?limit=47&offset=0"
//        curl "http://localhost:9000/users/coldstartuser/matches"   ← 5판만 존재 (표본 부족 케이스)
//        curl "http://localhost:9000/users/nosuchuser"              ← 404

const http = require("http");
const { URL } = require("url");

const PORT = process.env.PORT || 9000;

// 결정론적 난수 생성기 (매번 같은 fixture가 나오도록 — mulberry32)
function mulberry32(seed) {
  return function () {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function buildMatch(rng, index, tr) {
  const rounds = 3 + Math.floor(rng() * 8); // 3~10라운드 (베스트오브 형식 다양화)
  const win = rng() > 0.48;
  const roundResults = [];
  let selfWins = 0;
  let oppWins = 0;
  const winTarget = Math.ceil(rounds / 2);
  while (selfWins < winTarget && oppWins < winTarget) {
    const selfTakesRound = rng() > 0.45;
    roundResults.push({ alive: selfTakesRound });
    if (selfTakesRound) selfWins += 1;
    else oppWins += 1;
  }
  return {
    match_id: `m_${index}`,
    played_at: new Date(Date.now() - index * 3600_000).toISOString(),
    result: selfWins > oppWins ? "win" : "loss",
    self: {
      tr: Math.round(tr),
      apm: +(30 + rng() * 25).toFixed(2),
      pps: +(2 + rng() * 2).toFixed(2),
      vs: +(80 + rng() * 200).toFixed(2),
      garbage_sent: +(rng() * 40).toFixed(1),
    },
    opponent: {
      nickname: `Rival${(index % 7) + 1}`,
      tr: Math.round(tr + (rng() - 0.5) * 800),
      apm: +(30 + rng() * 25).toFixed(2),
      pps: +(2 + rng() * 2).toFixed(2),
      vs: +(80 + rng() * 200).toFixed(2),
      garbage_sent: +(rng() * 40).toFixed(1),
    },
    rounds: roundResults,
  };
}

function buildFixture(username, count, baseTr) {
  const rng = mulberry32([...username].reduce((a, c) => a + c.charCodeAt(0), 0) + count);
  const matches = [];
  let tr = baseTr;
  for (let i = 0; i < count; i++) {
    matches.push(buildMatch(rng, i, tr));
    tr += (rng() - 0.5) * 15;
  }
  return {
    profile: {
      username,
      rank: count < 10 ? "unranked" : "A",
      tr: Math.round(tr),
      glicko: Math.round(1500 + rng() * 500),
      rd: Math.round(40 + rng() * 30),
    },
    matches,
  };
}

// 고정 fixture 유저 (구축가이드 2.2절 — 콜드스타트 등 경계 케이스 고정용)
const USERS = {
  testuser: buildFixture("testuser", 60, 21500), // 정상 유저 (표본 충분). 실제 300판 대신 60판으로 축약 — 페이지네이션 검증에는 충분
  coldstartuser: buildFixture("coldstartuser", 5, 15000), // 표본 부족 (총 10판 미만, 기능명세서 3.6절 케이스)
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const send = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body, null, 2));
  };

  if (url.pathname === "/health") return send(200, { status: "ok" });

  let match = url.pathname.match(/^\/users\/([^/]+)\/matches$/);
  if (req.method === "GET" && match) {
    const username = match[1];
    const user = USERS[username];
    if (!user) return send(404, { success: false, error: "유저를 찾을 수 없습니다" });

    const limit = Math.min(parseInt(url.searchParams.get("limit") || "47", 10), user.matches.length || 1);
    const offset = parseInt(url.searchParams.get("offset") || "0", 10);
    const page = user.matches.slice(offset, offset + limit);
    return send(200, { total: user.matches.length, offset, limit, matches: page });
  }

  match = url.pathname.match(/^\/users\/([^/]+)$/);
  if (req.method === "GET" && match) {
    const username = match[1];
    const user = USERS[username];
    if (!user) return send(404, { success: false, error: "유저를 찾을 수 없습니다" });
    return send(200, { success: true, user: user.profile });
  }

  send(404, { error: "not found" });
});

server.listen(PORT, () => {
  console.log(`[tetrio-mock] listening on port ${PORT}`);
  console.log(`[tetrio-mock] fixture users: ${Object.keys(USERS).join(", ")}`);
});
