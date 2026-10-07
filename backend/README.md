# /backend

Spring Boot — API · 라우팅 · 인프라 호출부.

- 담당 브랜치: `backend` (정한비)
- `com/finesse/backend/calc/` 패키지는 `data-eng` 브랜치(위성훈) 담당이며 API/라우팅과 분리된 하위 패키지입니다.
- 두 브랜치가 같은 폴더를 다루므로 **자주 main에 병합하고, main을 자주 pull** 받으세요.

## 실행 환경
- Java 25, Spring Boot 4.1.1 (Gradle toolchain 25 — foojay 플러그인이 자동 설치)
- 기본 포트 8081, API 문서·테스트 화면 `/swagger-ui.html`
- 로컬 mock LLM: `java tools/MockLlmServer.java` (9101/9102)

## 구현 현황 (2026-10-01)

### 라이트뷰 1차 병합 — 사용자명 → calc 모듈
- `GET /api/v1/stats/{username}` → 소문자 정규화·캐시 확인 → `StatCalculatorFacade.analyze(username)`
  → 결과(`AnalysisOutcome`)를 응답으로 변환: Analyzed는 stats 응답, ColdStartBypass는 `cold_start=true`,
  UserNotFound는 404, CollectionFailed는 502
- TETR.IO 수집·정제·계산은 calc 모듈이 담당. 백엔드는 프로필 사진·XP·국가·가입일·플레이 시간·친구 수용 `GET /users/{username}`만 직접 호출 —
  calc와 같은 `RateLimiter`를 함께 써서 서버 전체 TETR.IO 호출이 1초 간격을 지킴(10/7)
- 백엔드는 calc의 공개 타입만 사용 — calc의 `ArchitectureTest`가 빌드 때 강제
- 설정: `BackendApplication`의 `@ConfigurationPropertiesScan` + `application.yml`의 `finesse.analytics.*`
  (TETR.IO 호출 최초 포함 3회, 페이지당 100판)
- 백엔드 임시 계산 코드(`RecordNormalizer`/`StatsCalculator`/`FancyMath`/`RivalAggregator`/`NicknameMasker`/`model.*`)는 삭제

### 그 밖의 기능
- `GET /api/v1/comment/{username}?scope=light|heavy` — LLM 오케스트레이션
  - 서버당 동시 1건(순차) + light 우선 대기열, 호출 1회/엔드포인트 타임아웃 분리, 연결 실패 서버 30초 제외(시도 횟수에 안 넣음)
  - light: LLM/AI 파트 설계 v1.2 요청 형식, 유효 하이라이트는 값이 있는 후보 키만, 목표 min(3, 후보 수) — 못 채우면 받은 만큼 200
  - heavy는 SSE(챕터별 `chapter` 이벤트 + `done` 요약, 10초 하트비트), 성공 챕터만 챕터 단위 캐시
- 캐싱 — stats/comment-light/comment-heavy 분리, stats 갱신 시 comment 캐시 연쇄 무효화
- 트래픽 몰림 — 캐시 미스 수집 동시 2건, 자리가 없으면 최대 6초 순서대로 대기, 대기 2명 초과면 503 SERVER_BUSY(Retry-After·retry_after_seconds 5)
- `docs/diagrams/` — 라이트·헤비 뷰 활동 다이어그램 (SSE·calc 모듈·503 BUSY 반영), `tools/MockLlmServer.java` — 로컬 mock LLM 서버

### calc 2차 결과 연결 (10/7, data-eng 6a012cd 병합)
- 콜드스타트 1~9판: `fixed_metrics.win_rate`·`recent_form`을 있는 경기만큼 채움 (0판은 승률 생략)
- `profile.apm`·`pps`·`vs` — calc `UserSummary`
- `fixed_metrics.tr_trend`(오래된 경기 → 최근)·`round_curves`(라운드별 평균 PPS·VS) — calc `MatchSeriesStats`.
  PPS 없는 라운드가 섞이면 `pps`는 빈 배열
- calc delta가 null(계산 가능한 매치 없음)이면 `playstyle_relative`·`attack`·`defense` 생략

### calc 모듈과 남은 협의
- `/users/{username}` 호출 자체를 calc로 이전 (선택 — 레이트리미터는 공유로 이미 일원화)
- 라이벌 반환 범위(반복 조우 상대만 vs 전체 상대), 헤비 8챕터 차트 데이터 스키마

### 검증
빌드·테스트 180개(실제 TETR.IO 호출 테스트 2개는 `-Dtetrio.live=true`일 때만, 10/7 calc 2차 병합 후 재실행해 통과) · 실제 TETR.IO + mock LLM으로 API·프론트 화면 확인.
