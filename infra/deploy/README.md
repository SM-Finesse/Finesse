# infra/deploy — 학교 PC 자동 배포 (CI/CD 3단계)

main 에 병합되면 몇 분 안에 학교 서버 PC의 backend·frontend 가 새 버전으로 바뀝니다.
서버 PC가 GitHub에 적힌 "배포할 버전"을 주기적으로 확인해 따라가는 방식(GitOps pull)입니다.

```
① main 병합
② GitHub Actions (docker-publish.yml): 이미지 빌드 → DockerHub 푸시 (:커밋7자리)
③ GitHub Actions: deploy 브랜치 target.env 에 TAG=<커밋7자리> 기록
④ 서버 PC (작업 스케줄러, 2분마다 deploy.ps1):
     target.env 확인 → 바뀌었으면 pull → 교체 → 정상 확인
     → 실패하면 직전 버전으로 자동 롤백
```

- 서버 PC에는 **비밀번호·토큰이 없습니다** (GitHub 공개 파일 + DockerHub 공개 이미지만 읽음)
- **배포 이력 = deploy 브랜치 커밋 이력**
- LLM 서버(301B-11·13)는 이 방식에서 제외 (계획대로 수동 관리)

## 파일

| 파일 | 내용 |
|---|---|
| `docker-compose.yml` | backend / frontend 서비스 (프로필로 PC별 선택) |
| `.env.example` | PC별 설정 예시 → `.env` 로 복사해서 사용 |
| `deploy.ps1` | 배포 1회 실행 (확인 → pull → 교체 → 정상 확인 → 실패 시 롤백) |
| `install-task.ps1` | `deploy.ps1` 을 작업 스케줄러에 등록/해제 |

PC별 `.env`:

| PC | `COMPOSE_PROFILES` | `BACKEND_UPSTREAM` |
|---|---|---|
| 리허설 (301B-24 한 대) | `backend,frontend` | `http://backend:8081` |
| 백엔드 (301B-26) | `backend` | (안 씀) |
| 프론트 (301B-28) | `frontend` | `http://192.168.0.126:8081` |

## 리허설 (301B-24, 한 대에 backend + frontend)

준비: Docker Desktop 실행 중, git 설치

```powershell
git clone https://github.com/SM-Finesse/Finesse.git C:\finesse
```
```powershell
cd C:\finesse\infra\deploy
```
```powershell
copy .env.example .env
```
```powershell
powershell -ExecutionPolicy Bypass -File .\deploy.ps1
```

확인할 것:

1. 마지막 줄 `[성공] <태그> 배포 완료` + `프론트→백엔드 연결 정상`
2. 브라우저 `http://localhost/` 화면이 뜨는지 (LLM 서버가 꺼져 있으면 코멘트만 실패할 수 있음)
3. 한 번 더 실행하면 아무 일도 안 하는지 (변경 없음)
4. 자동 실행 등록:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install-task.ps1
   ```
5. backend 나 frontend 를 수정해 main 에 병합 → 몇 분 뒤 `logs\deploy.log` 에 새 태그 배포 기록이 남는지
6. (롤백 시험) GitHub에서 deploy 브랜치 `target.env` 를 `TAG=없는태그` 로 수정 →
   로그에 `[실패] 이미지 받기 실패 … 기존 버전 그대로 유지`, 원래 태그로 되돌리면 다시 정상

정리: `.\install-task.ps1 -Uninstall` → `docker compose -p finesse down`

## 실제 서버 적용 (301B-26, 301B-28)

리허설과 같은 순서, `.env` 만 위 표대로 바꿉니다. 시작 전 체크리스트:

- [ ] 담당자 동의 (정한비 26, 호준수 28)
- [ ] **포트 충돌**: 그 PC에서 개발용 서버가 같은 포트(8081 / 80)를 쓰고 있지 않은지
  ```powershell
  netstat -ano | findstr LISTENING | findstr ":8081 :80 "
  ```
- [ ] Docker Desktop 설치 + "로그인 시 자동 시작" 켜기
- [ ] 방화벽 인바운드 허용 (관리자 PowerShell)
  ```powershell
  New-NetFirewallRule -DisplayName "Finesse backend 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow
  ```
  ```powershell
  New-NetFirewallRule -DisplayName "Finesse frontend 80" -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow
  ```
- [ ] 절전 "안 함", 재부팅 후 로그인 방법 (Docker Desktop 은 로그인해야 켜짐)
- [ ] 26 먼저 배포 → 28 배포 → 28 로그에 `프론트→백엔드 연결 정상`

## 운영

| 하고 싶은 것 | 방법 |
|---|---|
| 지금 무슨 버전인지 | `state\current_tag` 또는 GitHub deploy 브랜치 `target.env` |
| 배포 기록 | `logs\deploy.log` / deploy 브랜치 커밋 이력 |
| **롤백** | GitHub 웹에서 deploy 브랜치 `target.env` 의 TAG 를 이전 커밋 7자리로 수정 → 2~5분 뒤 반영 |
| 바로 반영 | 서버 PC에서 `.\deploy.ps1` 직접 실행 |
| 컨테이너 상태·로그 | `docker compose -p finesse ps` / `docker compose -p finesse logs --tail 100 backend` |

- 롤백으로 바꾼 TAG 는 **다음 main 병합 때 새 태그로 덮어써집니다** (문제 커밋을 고쳐서 병합하면 자연스럽게 정상화)
- 정상 확인에 실패한 태그는 `state\failed_tag` 에 기록되고, target.env 가 다른 태그로 바뀔 때까지 다시 시도하지 않습니다
- GitHub 원본 파일 캐시 때문에 반영까지 최대 5분 정도 걸릴 수 있습니다
- 작업 스케줄러 실행 때 PowerShell 창이 잠깐 깜빡일 수 있습니다 (정상)

## 정상 확인 기준

| 대상 | 주소 | 정상 |
|---|---|---|
| backend | `http://localhost:8081/` | 아무 HTTP 응답 (backend 에 /health 가 없어 404 도 "떠 있음"으로 봄) |
| frontend | `http://localhost/` | 200 |
| 프론트→백엔드 (참고) | `http://localhost/api/` | 502·504 가 아니면 연결됨 — 실패해도 롤백하지 않고 경고만 |

CI(`.github/workflows/ci.yml` 의 `cd-deploy`)가 이 폴더가 바뀔 때마다 리눅스에서
정상 배포 / 이미지 받기 실패 / 정상 확인 실패→롤백 3가지를 실제 이미지로 시험합니다.
