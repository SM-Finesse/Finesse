# /backend

Spring Boot — API · 라우팅 · 인프라 호출부.

- 담당 브랜치: `backend` (정한비)
- `com/finesse/backend/calc/` 패키지는 `data-eng` 브랜치(위성훈) 담당이며 API/라우팅과 분리된 하위 패키지입니다.
- 두 브랜치가 같은 폴더를 다루므로 **자주 main에 병합하고, main을 자주 pull** 받으세요.

## 구현 현황 (2026-09-29)

### 구현된 것
- TETR.IO API 연동 — 유저 조회, 최근 300판 수집, 소문자 정규화, rate limit 준수, 재시도 1회
- `GET /stats/{username}` — fixed_metrics(win_rate, tr_trend, recent_form) / delta_metrics / round_curves / rivals, 콜드스타트 처리
- `GET /comment/{username}?scope=light|heavy` — LLM 오케스트레이션
  - 서버당 동시 1건(순차) + light 요청 우선순위 큐
  - 호출 1회/엔드포인트 타임아웃 레이어 분리, 연결 실패 서버 30초 제외
  - 타임아웃 기준 문서(23번) 수치 그대로 반영
- 캐싱 — stats/comment-light/comment-heavy 분리, stats 만료 시 comment 캐시 연쇄 무효화
- `docs/diagrams/` — 활동 다이어그램 4개, `tools/MockLlmServer.java` — 로컬 목 LLM 서버

### 임시 구현 — data-eng 모듈로 교체 예정
`TetrioClient`/`RecordNormalizer`/`StatsCalculator`/`FancyMath`/`RivalAggregator`/`NicknameMasker`는
data-eng 브랜치(위성훈)의 calc 모듈과 기능이 겹치는 임시 구현입니다. 각 클래스에 대응 관계를 주석으로
남겨뒀습니다 (`TetrioProperties`↔`CollectorProperties`, `NormalizedMatch`↔`MatchHistory` 등).
data-eng 모듈이 준비되면 이 클래스들을 걷어내고 통합합니다.

### 검증
컴파일 · Spring context-load 테스트 · 실제 TETR.IO API + mock LLM 서버 라이브 호출로 확인.

### 커밋
- `7c2b6d5` — 초기 구현 (TETR.IO 연동/캐싱/LLM 오케스트레이션)
- `62a73ba` — 임시 구현 클래스에 data-eng 교체 예정 명시 (주석만, 동작 변경 없음)
