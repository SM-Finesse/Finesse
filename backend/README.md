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
- TETR.IO 수집·정제·계산은 calc 모듈이 담당. 백엔드는 프로필 사진·XP·국가·가입일용 `GET /users/{username}`만 직접 호출
- 백엔드는 calc의 공개 타입만 사용 — calc의 `ArchitectureTest`가 빌드 때 강제
- 설정: `BackendApplication`의 `@ConfigurationPropertiesScan` + `application.yml`의 `finesse.analytics.*`
  (TETR.IO 호출 최초 포함 3회, 페이지당 100판)
- 백엔드 임시 계산 코드(`RecordNormalizer`/`StatsCalculator`/`FancyMath`/`RivalAggregator`/`NicknameMasker`/`model.*`)는 삭제

### 그 밖의 기능
- `GET /api/v1/comment/{username}?scope=light|heavy` — LLM 오케스트레이션
  - 서버당 동시 1건(순차) + light 우선 대기열, 호출 1회/엔드포인트 타임아웃 분리, 연결 실패 서버 30초 제외
  - heavy는 SSE(챕터별 `chapter` 이벤트 + `done` 요약, 10초 하트비트), 성공 챕터만 챕터 단위 캐시
- 캐싱 — stats/comment-light/comment-heavy 분리, stats 갱신 시 comment 캐시 연쇄 무효화
- `docs/diagrams/` — 활동 다이어그램, `tools/MockLlmServer.java` — 로컬 mock LLM 서버

### calc 모듈에 요청할 보완 (1차 병합 후)
- 콜드스타트 결과에 최근 승패 포함 (지금은 승률 0.0%·승패 칸 빈 값)
- 매치 당시 TR 시계열(`tr_trend`)·라운드별 곡선(`round_curves`)
- `UserSummary`에 apm·pps·vs
- `/users/{username}` 호출을 calc로 이전 (레이트리미터 일원화)
- 폴더 `calc/matrics`와 package `calc.metrics` 불일치

### 검증
빌드·테스트 101개(실제 TETR.IO 호출 테스트 2개는 `-Dtetrio.live=true`일 때만) · 실제 TETR.IO + mock LLM으로 API·프론트 화면 확인.
