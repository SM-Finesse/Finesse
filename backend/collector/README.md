# 학습 데이터 수집기 (collect 브랜치 전용)

파인튜닝용 **LLM 입력(payload)** 을 실제 유저 데이터로 모은다. 정답 코멘트는 사람이 쓴다.
10/27부터 **LLM 서버를 설정하면 그 유저의 실제 LLM 코멘트(라이트 개별·기본 + 헤비 7챕터)도 받아 함께 저장**한다(설정이 없으면 예전처럼 부르지 않음).
기준 문서: 「학습 데이터 수집 서버 작업지시서」(20) · 「학습 데이터 CSV 구조 지시서」(19).

> 🔴 이 브랜치는 **main에 PR을 올리지 않는다.** 서비스 서버(`backend`)와 독립된 수집 전용 서버라 `backend`·`data-eng`의 변경을 받지 않고,
> 필요하면 calc도 이 브랜치에서 직접 고친다. 어느 코드로 계산했는지는 각 행의 `calc_commit`으로 남는다.

## 동작

```
targets.csv → 유저 1명씩: TETR.IO 4회(요약 1 + 기록 3) → calc 계산 1회
            → 서비스가 LLM에 보내려던 요청을 그대로 저장: light 1 + heavy 7 (rivals 제외)
              + calc 하이라이트 판정·선정 결과(highlight_judgement)와 라이트 코멘트 요청 {stat, value, zone}
            → (LLM 서버 설정 시) 개별 코멘트 × 선정 지표 수 + 기본 1 + 헤비 7 → 같은 JSON에 저장
            → users.csv에 결과 1행 → 직전 수집이 끝난 뒤 1분 대기 → 반복
            → 명단 끝: error·rate_limited로 끝난 유저를 한 번 더 → 창을 연 채 새 명단 대기
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
2. 스크립트가 jar를 다시 빌드하고 수집기를 띄운다. **비정상 종료면 횟수 제한 없이 다시 띄운다** — 1분, 연달아 죽으면 2·4·8분, 최대 10분 간격(10분 넘게 돌다 죽었으면 다시 1분부터). 수집기는 `users.csv`를 보고 이어서 한다. 다시 띄울 때마다 지금 코드로 다시 빌드한다.
3. 스크립트가 도는 동안 **PC가 절전 모드로 들어가지 않는다**(화면은 꺼져도 됨, 창을 닫으면 해제). 직접 절전·덮개 닫기·업데이트 재부팅은 막지 못한다.
4. **코드 변경을 커밋한 뒤 실행한다.** `calc_commit`(git HEAD 7자)이 실행 코드와 같아야 해서, 커밋 안 된 변경이 있으면 시작하지 않는다. 이렇게 시작을 거부하거나 빌드가 실패하면 **창은 멈추지 않고 10분마다 다시 빌드해 다시 시도**한다 — 커밋하면 저절로 재개된다(`-RetrySeconds`로 간격 조정).
5. **명단을 끝까지 돌아도 창을 닫지 않는다.** 1분마다 `targets.csv`를 확인해, 바뀌면 jar를 다시 빌드하고 같은 창에서 이어서 수집한다. 새 창을 띄울 필요 없이 명단에 유저만 추가하면 된다(`-Once`면 예전처럼 끝낸다).
6. **창은 하나만** — 이미 다른 창이 수집 중이거나 기다리는 중이면 새로 띄운 창은 바로 끝난다.

| 종료 코드 | 뜻 | 스크립트 |
|---|---|---|
| 0 | 명단 끝까지 처리 | 창을 연 채 새 명단 대기(`-Once`면 멈춤) |
| 1 | 설정 오류(명단 없음·커밋 안 된 변경 등) | 10분 뒤 다시 빌드해 다시 시도(`-Once`면 멈춤) |
| 2 | 중단 — `failure-pause-seconds`를 0으로 둔 경우의 연속 실패, 또는 대기 중 중단 요청 | 멈춤 |
| 3 | 다른 수집기·창이 이미 실행 중 | 멈춤 |
| 그 밖 | 비정상 종료 | 다시 띄움 — 1·2·4·8·10분 간격, 횟수 제한 없음(`-MaxRestarts`로 제한 가능) |

설정(`application-collector.yml`, `--collector.*`로 덮어쓰기): `interval-seconds`(60) · `rate-limited-interval-seconds`(429 뒤 300) · `max-consecutive-failures`(3) · `failure-pause-seconds`(연속 실패 뒤 600, 0이면 중단) · `slow-user-warn-seconds`(30) · `calc-commit`(git이 없는 PC용) · `allow-dirty`.

### LLM 코멘트 (10/27)

- 켜는 법: `DataDir\collector-local.yml`에 서버 주소를 넣는다(실제 주소는 레포에 넣지 않는다). 파일이 없거나 비어 있으면 LLM을 부르지 않는다.
  ```yaml
  collector:
    llm:
      servers: [http://(LLM 서버 1):8081, http://(LLM 서버 2):8081]
  ```
- 1번 서버와 같은 엔드포인트: `/v1/light/sentence`(선정 지표마다) · `/v1/light/summary`(1회) · `/v1/comment/heavy-chapter`(7챕터). 요청 모양도 1번 서버와 같다(선정은 calc `highlight_judgement.picks`, 값은 화면 원값, 영역은 높음·보통·낮음).
- 1번 서비스와 GPU를 나눠 쓰므로 **한 번에 하나씩, 서버를 번갈아** 보낸다. 유저당 약 20~40초 더 걸린다.
- 호출 1회 10초. 연결 실패·시간 초과·5xx면 다른 서버로. **대체 문장(`fallback: true`)이면 같은 요청을 1회 더**(`fallback-retries`), 그래도 대체 문장이면 그대로 저장하고 `fallback: true`로 남긴다. 4xx는 다시 보내지 않는다.
- 코멘트를 못 받아도 수집 결과는 `ok` — 사유는 JSON `error`에 남는다. 로그: `u0001 LLM 코멘트 11건 (25.3초)` · `… 중 못 받음 n · 대체 문장 n`.
- 느린 수집 경고(`slow-user-warn-seconds`)는 LLM 코멘트 시간을 빼고 본다.

### 실패 처리 (작업지시서 4.6절에서 바꾼 부분 — PM 확인 10/13)

- **연속 3회 실패하면 중단하지 않고 10분 쉰 뒤 이어서 수집한다.** 쉬고 나서 처음 유저가 또 실패하면 3번을 채우지 않고 바로 다시 10분 — TETR.IO 장애가 길어도 10분에 1명만 쓰고 기다린다. 한 명이라도 성공하면 다시 3번 기준. 쉴 때마다 경고 로그를 남긴다(지시서가 걱정한 "조용히 계속 도는" 상황은 아님).
- **`error`·`rate_limited`로 끝난 유저는 명단을 한 바퀴 돈 뒤 한 번 더 시도한다.** 두 번째 결과로 확정. `users.csv`에 같은 `user_key` 행이 하나 더 붙고, **나중 행이 최종 결과**다(CSV 변환기도 나중 행을 쓴다 — `split`도 나중 행에 채운다). 지난 실행에서 실패로 끝난 유저도 다음 실행에서 한 번 더 시도한다. `not_found`·`insufficient`는 다시 해도 같아서 시도하지 않는다.

### 로그 (원인 추적용)

닉네임·요청 URL은 남기지 않는다(예외는 클래스 이름만).
- `TETR.IO summary|records 요청 실패 — <이유> [원인 클래스] (초)` — 요청 1번이 실패할 때마다
- `TETR.IO 재요청 n/2 — x초 뒤 다시 보냅니다` · `TETR.IO 요청이 n번 시도 모두 실패했습니다`
- `TETR.IO … 요청이 x초 걸렸습니다(기준 10초)` — 실패 없이 느리기만 한 요청(응답이 조금씩 오거나 DNS가 늦으면 5초 타임아웃에 안 걸린다)
- `TETR.IO 안전장치(CircuitBreaker) CLOSED_TO_OPEN` 등 — 오류가 몰려 30초 동안 요청을 막을 때
- `u0147 수집이 156초 걸렸습니다(기준 30초)` — 유저 1명이 `slow-user-warn-seconds`를 넘으면

## 저장 파일 (`DataDir`)

| 파일 | 내용 | 레포 |
|---|---|---|
| `targets.csv` | 수집 대상 (입력) | 🔴 밖 |
| `user_map.csv` | `user_key` ↔ username | 🔴 밖 |
| `users.csv` | 유저별 결과 = 진행 기록. 열: `user_key, collected_at, status, fail_reason, match_count, rank, rd, split`. `split`은 PM이 채운다 | `.gitignore` |
| `collected/u0001_light.json`, `u0001_heavy_<chapter>.json` | 유저당 8개. 메타(`user_key`·`collected_at`·`calc_commit`·`match_count`) + `payload`. light에는 `highlight_judgement`(calc 판정·선정)·`light_comment`(`items` = 코멘트 요청 {stat, value, zone}과 각 `comment`, `summary`, `requested`), heavy에는 `comment`(10/27). 코멘트는 `{문장, fallback, version, attempts, error}` | `.gitignore` |
| `collector-local.yml` | LLM 서버 주소 등 로컬 설정(10/27) | 🔴 밖 |
| `logs/collector.log` | 10MB·30개 롤링 | |
| `collector.lock` | 단일 실행 잠금 | |

`users.csv` status: `ok` · `insufficient`(표본 부족 1·2·3단계, 정상 제외) · `not_found`(404·닉네임 형식 오류) · `rate_limited`(429 — 다음 수집까지 5분) · `error`(5xx·타임아웃 등). `rate_limited`·`error`가 연속 3번이면 10분 쉬고, 명단 끝에서 한 번 더 시도.

`users.csv` `rank`·`rd` (10/13 추가, 19번 지시서 3절에 없는 열) — 수집 시점 TETR.IO 리그 요약 값. 리그 요약을 받은 `ok`·`insufficient`만 채우고 나머지는 빈 칸.
- `rank`: 대문자 티어(`X+` … `D`). **`Z` = 미배치이거나 RD가 100을 넘은 유저** — 인게임에서는 `?`로 보인다. TETR.IO 문서: "`z` is unranked", rd "If over 100, this user is unranked".
- `rd`: Glicko-2 RD, 소수 둘째 자리. 10판 미만이면 `-1`. 게임을 쉬면 올라간다(리그 요약 `decaying` = 최근 1주 안 함).
- 예전 머리글의 `users.csv`는 시작할 때 새 머리글로 다시 쓴다(`split`은 그대로). 열이 생기기 전에 수집한 `ok`·`insufficient` 유저는 시작할 때 리그 요약을 1회씩 조회해 채운다 — **수집 시점이 아니라 채운 시점 값**이다. 실패하면 멈추고 다음 시작 때 이어서.

## JSON → CSV

```powershell
.\gradlew.bat exportTrainingCsv --args="--data-dir=D:\finesse-collector"
```

`DataDir\export\light.csv`(46열) · `heavy.csv`(31열, 유저당 7행). UTF-8 BOM. 요청에 없는 필드는 빈 칸, `null` 값은 문자 `null`, 0은 값.

10/27 추가 열 — 기존 열 뒤에 붙였다(앞 열 순서는 그대로). 10/27 전에 수집한 JSON은 빈 칸.
- `light.csv`: `pick1~3_stat`을 calc 선정 지표로 채운다(사람이 쓰는 `pick*_sentence`·`light_summary`는 그대로 빈 칸). 끝에 `ranges_version`·`range_group`·`pick1~3_value`·`pick1~3_zone`·`llm_pick1~3_sentence`·`llm_pick1~3_fallback`·`llm_light_summary`·`llm_summary_fallback`·`llm_version` (17열).
- `heavy.csv`: 끝에 `llm_footnote`·`llm_footnote_fallback`·`llm_version` (3열). 사람이 쓰는 `footnote`는 빈 칸.

**학습 데이터는 1번 서버가 LLM에 실제로 보내는 값만 담는다** (PM 10/12: "현재 모듈 설계를 토대로, 충돌을 줄이게 1번 서버 형태를 활용").
CSV 구조 지시서(19번)와 다른 점 — 학습 입력과 실제 서비스 입력이 같아야 해서다.
- `light.csv`: `recent_form_json` 열 없음(지시서 30열 → 29열). 1번 서버 light 요청에 `recent_form`이 없다.
- `heavy.csv`: 역전 챕터 표본 수 4열 추가(지시서 24열 → 28열) — `comeback_opportunities`·`comeback_won`·`comeback_against_opportunities`·`comeback_against_allowed`. 1번 서버 역전 챕터 data에 들어 있다.
`split`을 채운 뒤 다시 돌리면 반영된다.

## 알아둘 것

- **이 브랜치에서 calc를 고친 곳 (429 구분)** — 원래 calc는 429·5xx·타임아웃을 모두 "수집 실패"로만 알려준다. 계산 공식은 건드리지 않고 429만 위로 전달하게 했다: `TetrIoRateLimitedException`(신규) · `TetrIoApiClient`(429 catch) · `CollectionStatus.RATE_LIMITED` · `TetrIoCollector`·`StatCalculatorFacade`(전달).
- **이 브랜치에서 calc를 고친 곳 (로그)** — `TetrIoApiClient`(요청 실패·느린 요청), `TetrIoResilience`(재요청·CircuitBreaker 상태). 로그만 추가, 동작·계산은 그대로.
- **calc를 data-eng `cfa2c0e`(10/27)까지 옮겨 왔다** — 하이라이트 판정·선정(v4.3, 티어 묶음 범위 G2·역전 축소 k=20), `session_vs_slope` 정의 불가 시 null(v4.4), 지표 신뢰도. data-eng를 merge하지 않고 바뀐 파일을 새 커밋으로 넣었다(merge하면 `collect-guard` 때문에 그쪽 main PR이 막힌다). 위 두 가지 collect 전용 변경은 그대로 남아 있다. 1번 서버(backend `658de2b`)와 같은 calc라 같은 입력이면 같은 값이 나온다.
- 기록 수집이 429로 끊겨도 30판 이상 모였으면 calc가 기존처럼 일부만으로 분석한다(`ok`).
- 캐시를 타지 않는다(서비스 엔드포인트·캐시 경로를 거치지 않음) — 3,800명을 돌려도 메모리가 쌓이지 않는다.
