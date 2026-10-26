# Finesse 통합 운영 서버 간편 설치기

학교 PC 한 대를 **통합 운영 서버**(사이트·CD, Uptime Kuma, 자동 QA, heartbeat, Cloudflare Tunnel)로 만든다.
2026-10-26 301B-24 → 301B-12 이전 때 손으로 한 순서를 그대로 스크립트로 옮겼다.

## 준비물

```
infra\installer\
  install.ps1     설치기 본체
  install.bat     더블클릭 → 관리자 권한으로 설치
  check.bat       더블클릭 → 점검만 (아무것도 안 바꿈)
  backup\         ← 이전 서버 백업을 여기에 둔다 (레포에 올리지 않음)
```

`backup\` 에 필요한 파일 (이전 서버에서 만든 백업):

| 파일 | 내용 |
|---|---|
| `finesse-local.zip` | `C:\finesse` 에 덮어쓸 `.env`·`state`·`logs`·`infra\ops` |
| `finesse-ops.zip` | `finesse-ops\` (monitoring compose, heartbeat 스크립트) |
| `finesse-qa.zip` | `finesse-qa\` (run-qa.bat, Postman 컬렉션, ui) |
| `uptime-kuma-data.tar.gz` | Kuma 데이터 (모니터·알림·상태 화면 설정) |
| `task-heartbeat.xml` | 작업 "Finesse Heartbeat" — **Ping URL 포함, 비밀값** |
| `cloudflared-token` | 터널 토큰 — **비밀값** |

백업 폴더 안에 같은 이름 폴더가 한 번 더 있어도(`backup\finesse-backup\...`) 찾아서 쓴다. 다른 곳에 있으면 `-BackupDir` 로 지정.

## 쓰는 법

1. `check.bat` 으로 지금 상태를 본다
2. `install.bat` 실행. 중간에 멈추면 안내대로 해결하고 **다시 실행** — 이미 된 단계는 건너뛴다
3. 끝나면 결과 요약과 "사람이 할 일" 목록이 나온다
4. 재부팅 → 손대지 않고 3~5분 뒤 `check.bat` → 전부 "이미 됨/완료"면 끝

옵션 (관리자 PowerShell):

```powershell
.\install.ps1 -CheckOnly
.\install.ps1 -BackupDir D:\finesse-backup
.\install.ps1 -GitName 301B-00 -GitEmail you@example.com
.\install.ps1 -SkipTunnel        # 이전 서버의 터널이 아직 켜져 있을 때
.\install.ps1 -SkipAutoLogon     # 비밀번호 있는 계정
.\install.ps1 -Yes               # 확인 질문에 모두 예
```

## 단계

| # | 단계 | 자동 | 사람 |
|---|---|---|---|
| 0 | 사전 점검 | 관리자 권한, 백업 6개, 가상화, 실행 정책 | 가상화가 꺼져 있으면 BIOS |
| 1 | 프로그램 | Git, Node LTS, cloudflared (winget), WSL | WSL 설치 후 재부팅 1회 / Docker Desktop 설치(All-users)·첫 실행 |
| 2 | 기본 설정 | 절전 끄기, 자동 로그온, WSL 메모리 8GB, 방화벽 80·3001 | Docker "로그인 시 자동 시작" 체크 |
| 3 | 배치 | 레포 clone, zip 3개, 다운로드 차단 해제 | |
| 4 | 사이트 + CD | Docker 엔진 대기, `deploy.ps1` 로 사이트 기동, `install-task.ps1` 로 배포 작업 | |
| 5 | 모니터링 | Kuma 볼륨이 **없을 때만** 복원 → 기동 (있는 데이터는 덮어쓰지 않음) | |
| 6 | QA + heartbeat | Newman 6.2.2 + htmlextra 1.23.1, ui 패키지, Playwright 크롬, 작업 2개 | |
| 7 | 터널 | 이전 서버 터널이 꺼졌는지 확인 후 서비스 등록 (토큰은 화면에 안 띄움) | 이전 서버 터널 끄기 |
| 8 | 콘솔 | `console.config.json` 이름을 이 PC 로 | |
| 9 | 최종 확인 | 컨테이너 4개, 응답 3~4개, 터널, 작업 3개 | Healthchecks 이름, 팀 공지 |

## 10/26 이전 때 겪은 함정 (설치기에 반영)

- `VirtualizationFirmwareEnabled = False` 여도 BIOS 가상화가 켜져 있을 수 있음 → 하이퍼바이저·Dell cctk 로 다시 확인하고, 그래도 모르면 경고만 하고 진행
- Windows 11 Home 기본 실행 정책(Restricted) 때문에 `npm` 이 막힘 → RemoteSigned 로 변경, 스크립트 안에서는 `npm.cmd` 사용
- 백업 README 등 UTF-8 파일은 PowerShell 5.1 에서 `-Encoding UTF8` 로 읽어야 한글이 안 깨짐 (이 스크립트도 UTF-8 BOM 으로 저장)
- 작업 결과 `267011`(0x41303)은 "아직 실행 전" — 실패가 아님
- Kuma 는 컨테이너를 먼저 켜면 빈 DB 를 만들어 버림 → `compose create` → 복원 → `up -d`
- 백업의 배포/QA 작업 XML 은 이전 PC 계정 SID 가 박혀 있고 `Enabled=false` 였음 → `install-task.ps1`·명령으로 새로 등록. heartbeat XML 은 SYSTEM(고정 SID)이라 그대로 사용
- 자동 QA 의 U-08(개발 서버 전용 검사)은 운영 빌드 대상이라 실패함 → Kuma "API 테스트"가 빨강일 수 있음 (처리 보류 중)

자세한 기록: 프로젝트 문서 `finesse-12-migration-2026-10-26.md`
