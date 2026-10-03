# Finesse 개발환경 프로토타입

**[2026-10-02] `HJ2002-star/DevOps-prototype`(박덕현, DevOps/QA)에서 `infra` 브랜치로 동기화됨.**
원본 레포는 그대로 유지되며, 이후 변경 사항도 이 디렉터리에 반영 예정입니다. 아래 실행
명령은 모두 이 디렉터리(`infra/mocks`)에서 실행하는 것을 전제로 합니다 — 레포 루트가 아닙니다.

**상태: 팀 공식 채택 확정 (2026-09-10 회의) — [2026-09-22 갱신]** scope=heavy(tr_trend_delta
챕터) 구현, 하이라이트 후보 풀에서 tr_trend_delta 제외, 포트 환경변수화(LLM_MOCK_1_PORT 등) 반영.
**[2026-09-29 갱신]** LLM/AI 담당용 `llm-payload-mock` 신규 추가 (더미 LLM 입력 payload 생성 스크립트, 임시).

Mock서버도입제안 / Mock개발환경-구축가이드 문서(2026-09-03)의 아이디어에서 시작한 프로토타입입니다.

## 포함된 것

- **LLM Mock 서버 2개 인스턴스** (`llm-mock-1`, `llm-mock-2`) — 기능명세서 3.4/3.5절 스키마 그대로 따름. scope=light 전체 + **scope=heavy는 tr_trend_delta 챕터만 우선 구현**(적응형 N값 로직 포함). 백엔드의 라운드로빈 분배 로직(FR-12)을 두 인스턴스 상대로 검증할 수 있음
- 정상 응답 + 데모용 예외 모드 4종 (지연 / 서버 오류 / 하이라이트 개수 오류 / **입력에 없는 지표는 하이라이트로 고르지 않음**)
- **TETR.IO API Mock** (`tetrio-mock`) — 유저 조회 + 매치 히스토리(상대 스탯 포함) 고정 fixture. 표본 부족(콜드스타트) 케이스 포함
- `docker compose up` 한 번으로 3개 서비스 전부 실행. 호스트 포트는 환경변수(`LLM_MOCK_1_PORT`, `LLM_MOCK_2_PORT`, `TETRIO_MOCK_PORT`)로 재정의 가능

## 제외된 것 (회의에서 논의 필요)

- scope=heavy의 나머지 7개 챕터(플레이스타일/공격효율/수비/상대강도별승률/역전승/컨디션변화/라이벌) — tr_trend_delta만 우선 구현됨
- meta.retry_count — **이 저장소 범위 밖으로 정정.** "백엔드가 LLM/Mock 응답 형식 오류로 재요청한 횟수"를 세는 필드라, 백엔드 자신의 응답 스키마에 들어가야 함. mock-llm은 상태가 없는 스텁이라 재시도 여부를 알 수 없어 한때 넣었다가 (2026-09-22) 제거함
- QA 예외 모드의 정식 스펙 (지금 있는 것은 데모용 임시 버전)
- CI 파이프라인 연동
- 실제 프론트엔드/백엔드/캐시(Redis) — 이건 원래 다른 역할(프론트·백엔드) 담당이라 이 저장소 범위 밖

## 실행 방법

```bash
docker compose up --build -d
# 포트를 바꾸고 싶다면 예: LLM_MOCK_1_PORT=9101 docker compose up --build -d
```

| 서비스 | 포트 (기본값) | 포트 환경변수 | 역할 |
|---|---|---|---|
| `llm-mock-1` | `localhost:9001` | `LLM_MOCK_1_PORT` | LLM Mock (1번 서버) |
| `llm-mock-2` | `localhost:9002` | `LLM_MOCK_2_PORT` | LLM Mock (2번 서버, 라운드로빈 대상) |
| `tetrio-mock` | `localhost:9003` | `TETRIO_MOCK_PORT` | TETR.IO API Mock |

## 테스트 방법 — LLM Mock

```bash
# 정상 응답 (하이라이트 3개) — 1번/2번 서버 둘 다 동일하게 동작
curl "http://localhost:9001/api/v1/comment/testuser?scope=light"
curl "http://localhost:9002/api/v1/comment/testuser?scope=light"

# 입력에 있는 지표만 하이라이트로 고르기 (delta_comeback이 후보에서 빠진 유저 흉내내기)
curl "http://localhost:9001/api/v1/comment/testuser?scope=light&stats=delta_plonk,strength_split,session_vs_slope"

# 하이라이트 4개 반환 (형식 오류 시뮬레이션 - 백엔드가 앞 3개만 쓰는지 확인용)
curl "http://localhost:9001/api/v1/comment/testuser?scope=light&mode=excess"

# 하이라이트 1개만 반환 (재요청 로직 검증용)
curl "http://localhost:9001/api/v1/comment/testuser?scope=light&mode=few"

# 500 에러 시뮬레이션
curl -i "http://localhost:9001/api/v1/comment/testuser?scope=light&mode=error"

# 3초 지연 (프론트 로딩 스켈레톤 확인용)
curl "http://localhost:9001/api/v1/comment/testuser?scope=light&mode=delay"

# 헬스체크
curl "http://localhost:9001/health"
```

## 테스트 방법 — LLM Mock (scope=heavy, 신규)

tr_trend_delta 챕터만 우선 구현되어 있습니다. `totalGames`로 유저의 전체 판수를 넘기면
데이터 명세서 10절의 적응형 N값 표에 따라 N이 계단식으로 결정됩니다.

```bash
# 30~59판 구간 → N=15
curl "http://localhost:9001/api/v1/comment/testuser?scope=heavy&totalGames=45"

# 정확히 60판 (경계값) → 상위 구간 N=20 이 적용되는지 확인
curl "http://localhost:9001/api/v1/comment/testuser?scope=heavy&totalGames=60"

# 10판 미만 → 콜드스타트, tr_trend_delta 값 자체가 노출되지 않아야 함(exposed:false)
curl "http://localhost:9001/api/v1/comment/testuser?scope=heavy&totalGames=8"

# totalGames를 안 주면 기본값 60 사용 (tetrio-mock의 testuser 판수와 동일)
curl "http://localhost:9001/api/v1/comment/testuser?scope=heavy"
```

## 테스트 방법 — TETR.IO Mock

```bash
# 유저 조회 (정상)
curl "http://localhost:9003/users/testuser"

# 매치 히스토리 (페이지네이션 — limit/offset)
curl "http://localhost:9003/users/testuser/matches?limit=47&offset=0"

# 표본 부족(콜드스타트) 유저 — 5판만 존재
curl "http://localhost:9003/users/coldstartuser/matches"

# 존재하지 않는 유저 — 404
curl "http://localhost:9003/users/nosuchuser"

# 헬스체크
curl "http://localhost:9003/health"
```

주의: 실제 TETR.IO 공식 API의 정확한 필드명/경로는 아직 대조 확인 전입니다(백엔드 API 명세서
10절 미정 사항). 이 Mock의 응답 형태는 백엔드가 파싱 로직을 미리 연습할 수 있는 수준의
근사치이며, 실제 API 스펙이 확정되면 함께 갱신해야 합니다.

## LLM Payload Mock (LLM/AI 담당 전용, 임시)

위 두 Mock과는 방향이 반대입니다 — mock-llm은 LLM이 "낼 법한 출력"을 흉내내고, 이건 LLM이
"받을 입력(payload)"을 흉내냅니다. 백엔드 계산 파이프라인이 없어도 실제 Qwen 모델에 넣어볼
입력값을 형식만 맞춰 뽑을 수 있습니다. **파인튜닝 완료 전까지만 쓰는 임시 도구이며, 상시
서버가 아니라 1회성 스크립트입니다** — `docker compose up`만으로는 뜨지 않고 profile을
명시해야 실행됩니다.

```bash
# light — delta_metrics 11개 필드(playstyle 4 + attack 2 + defense 2 + strength_split
# + delta_comeback + session_vs_slope)
docker compose --profile llm-payload run --rm llm-payload-mock testuser

# heavy — 위 11개 + tr_trend_delta 추가 (라이트 11개 후보에서는 제외되는 heavy 전용 값)
docker compose --profile llm-payload run --rm llm-payload-mock testuser --scope=heavy --totalGames=45

# 콜드스타트 (10판 미만) — payload 자체가 없음
docker compose --profile llm-payload run --rm llm-payload-mock testuser --totalGames=8

# Docker 없이 바로
cd llm-payload-mock && node generate-dummy-payload.js testuser --scope=heavy
```

같은 유저명이면 항상 같은 결과가 나옵니다(결정론적 난수). 요청 형식은 "Finesse — 하이라이트
지표 설계" 문서(2026-09-30 작성, **PM 검수 대기 — 아직 최종 확정 아님**) 2~3절의 11개 후보 ·
`fixed_metrics`/`delta_metrics` 스키마를 그대로 따릅니다. **[2026-10-01 정정]** 이전 버전은
"TETR.IO 분석 데이터 파이프라인 모듈 설계서 v1.4" 17~18절 기준 9종 categorical
(`styleSignals`/`highlightCandidates`/`summaryHint` enum) 구조였는데, 그 근거 자체가 다른
정본 문서들과 맞지 않는 것으로 확인되어 폐기했습니다. 지금은 raw delta 값을 그대로
LLM에 보냅니다(구버전의 "원시 delta 금지" 규칙은 새 문서에서 사라짐).

알려진 단순화 사항(표본 부족 게이팅이 실제로는 2단계인데 이 스크립트는 1단계만 흉내냄,
null 확률이 실제 계산이 아닌 임의 확률인 점, `--totalGames` 입력 미검증 등)은 팀 공유 문서
"Finesse-MockLLM-통합안내" 13장에 전부 기록되어 있습니다(갱신 예정).

## Docker 없이 바로 테스트하고 싶다면

```bash
cd mock-llm && PORT=9001 node server.js   # 한 터미널
cd mock-llm && PORT=9002 node server.js   # 다른 터미널
cd tetrio-mock && PORT=9003 node server.js  # 또 다른 터미널
```

외부 패키지 설치가 필요 없어서 (Node.js 내장 http 모듈만 사용) `npm install` 없이 바로 됩니다.


