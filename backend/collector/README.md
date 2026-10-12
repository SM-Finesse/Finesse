# 학습 데이터 수집기 (collect 브랜치 전용)

파인튜닝용 **LLM 입력(payload)** 을 실제 유저 데이터로 모은다. 정답 코멘트는 사람이 쓰고, **LLM은 부르지 않는다**.
기준 문서: 「학습 데이터 수집 서버 작업지시서」(20) · 「학습 데이터 CSV 구조 지시서」(19).

> 🔴 이 브랜치는 **main에 PR을 올리지 않는다.** 서비스 서버(`backend`)와 독립된 수집 전용 서버라 `backend`·`data-eng`의 변경을 받지 않고,
> 필요하면 calc도 이 브랜치에서 직접 고친다. 어느 코드로 계산했는지는 각 행의 `calc_commit`으로 남는다.

## 동작

```
targets.csv → 유저 1명씩: TETR.IO 4회(요약 1 + 기록 3) → calc 계산 1회
            → 서비스가 LLM에 보내려던 요청을 그대로 저장: light 1 + heavy 7 (rivals 제외)
            → users.csv에 결과 1행 → 직전 수집이 끝난 뒤 1분 대기 → 반복
```

- 서비스 코드 경로를 그대로 쓴다: `StatsService` 매핑 → `LlmLightRequest.from` / `CommentService.buildChapterData`. 재조립하지 않는다.
- 웹 서버를 띄우지 않는다(`collector` 프로파일). 프로필용 `/users` 호출은 막는다.
- 닉네임은 소문자로 바꿔 호출하고, 로그에는 `user_key`만 남긴다.

## 실행

```powershell
# 저장 위치는 복원 프로그램이 지우지 않는 곳으로 (DevOps 조사 후 확정)
powershell -ExecutionPolicy Bypass -File collector\run-collector.ps1 -DataDir D:\finesse-collector
```

1. `-DataDir`에 `targets.csv`(머리글 `username` 한 열)를 먼저 넣는다.
2. 스크립트가 jar를 다시 빌드하고 수집기를 띄운다. 비정상 종료면 1분 뒤 다시 띄우고, 수집기는 `users.csv`를 보고 이어서 한다.
3. 스크립트가 도는 동안 **PC가 절전 모드로 들어가지 않는다**(화면은 꺼져도 됨, 창을 닫으면 해제). 직접 절전·덮개 닫기·업데이트 재부팅은 막지 못한다.
4. **코드 변경을 커밋한 뒤 실행한다.** `calc_commit`(git HEAD 7자)이 실행 코드와 같아야 해서, 커밋 안 된 변경이 있으면 시작하지 않는다.

| 종료 코드 | 뜻 | 스크립트 |
|---|---|---|
| 0 | 명단 끝까지 처리 | 멈춤 |
| 1 | 설정 오류(명단 없음·커밋 안 된 변경 등) | 멈춤 |
| 2 | 연속 3회 실패로 중단 — 원인 확인 후 다시 실행하면 이어서 | 멈춤 |
| 3 | 다른 수집기가 이미 실행 중 | 멈춤 |
| 그 밖 | 비정상 종료 | 1분 뒤 재시작(최대 20회) |

설정(`application-collector.yml`, `--collector.*`로 덮어쓰기): `interval-seconds`(60) · `rate-limited-interval-seconds`(429 뒤 300) · `max-consecutive-failures`(3) · `calc-commit`(git이 없는 PC용) · `allow-dirty`.

## 저장 파일 (`DataDir`)

| 파일 | 내용 | 레포 |
|---|---|---|
| `targets.csv` | 수집 대상 (입력) | 🔴 밖 |
| `user_map.csv` | `user_key` ↔ username | 🔴 밖 |
| `users.csv` | 유저별 결과 = 진행 기록. `split`은 PM이 채운다 | `.gitignore` |
| `collected/u0001_light.json`, `u0001_heavy_<chapter>.json` | 유저당 8개. 메타(`user_key`·`collected_at`·`calc_commit`·`match_count`) + `payload` | `.gitignore` |
| `logs/collector.log` | 10MB·30개 롤링 | |
| `collector.lock` | 단일 실행 잠금 | |

`users.csv` status: `ok` · `insufficient`(표본 부족 1·2·3단계, 정상 제외) · `not_found`(404·닉네임 형식 오류) · `rate_limited`(429 — 다음 수집까지 5분) · `error`(5xx·타임아웃 등). `rate_limited`·`error`가 연속 3번이면 중단.

## JSON → CSV

```powershell
.\gradlew.bat exportTrainingCsv --args="--data-dir=D:\finesse-collector"
```

`DataDir\export\light.csv`(30열) · `heavy.csv`(24열, 유저당 7행). UTF-8 BOM. 요청에 없는 필드는 빈 칸, `null` 값은 문자 `null`, 0은 값.
`split`을 채운 뒤 다시 돌리면 반영된다.

## 알아둘 것

- **이 브랜치에서 calc를 고친 곳 (429 구분)** — 원래 calc는 429·5xx·타임아웃을 모두 "수집 실패"로만 알려준다. 계산 공식은 건드리지 않고 429만 위로 전달하게 했다: `TetrIoRateLimitedException`(신규) · `TetrIoApiClient`(429 catch) · `CollectionStatus.RATE_LIMITED` · `TetrIoCollector`·`StatCalculatorFacade`(전달).
- 기록 수집이 429로 끊겨도 30판 이상 모였으면 calc가 기존처럼 일부만으로 분석한다(`ok`).
- 캐시를 타지 않는다(서비스 엔드포인트·캐시 경로를 거치지 않음) — 3,800명을 돌려도 메모리가 쌓이지 않는다.
