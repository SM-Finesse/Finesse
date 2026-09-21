# Finesse

TETR.IO 매치 히스토리를 분석하고 AI 코멘터리를 생성하는 웹 서비스.

## 레포 구조 (모노레포)

| 폴더 | 용도 |
|---|---|
| `/backend` | Spring Boot (API · 라우팅 · 인프라 호출부) |
| `/backend/.../com/finesse/backend/calc/` | 계산 로직 패키지 (data-eng 담당, API/라우팅과 분리) |
| `/frontend` | React + Vite + Tailwind |
| `/llm-server` | LLM 파인튜닝 · 추론 관련 코드 |
| `/infra` | Docker, docker-compose, k8s 매니페스트 |
| `/docs` | 필요 시에만 사용 (문서·일정의 주 도구는 Notion) |
| `/.github` | CODEOWNERS, PR/이슈 템플릿, CI 워크플로 |

## 브랜치 (담당별 상시 브랜치)

| 브랜치 | 담당 | 대상 영역 | 이동 명령 |
|---|---|---|---|
| `main` | 전체 | 직접 push 금지, PR로만 병합 | - |
| `frontend` | 호준수 | `/frontend` | `git switch frontend` |
| `backend` | 정한비 | `/backend` (calc 제외) | `git switch backend` |
| `data-eng` | 위성훈 | `/backend/.../calc/` | `git switch data-eng` |
| `llm` | 윤세연 | `/llm-server` | `git switch llm` |
| `infra` | 박덕현 | `/infra`, `.github/workflows` | `git switch infra` |

## 빠른 시작

```bash
git clone https://github.com/SM-Finesse/Finesse.git
cd Finesse
git config user.name "본인 이름"
git config user.email "GitHub에 등록한 이메일"
git switch <내 브랜치>      # 위 표의 이동 명령을 그대로 입력
```

## 하루 작업 흐름

```bash
git switch <내 브랜치>
git pull origin main                 # main 최신 내용 받기
# ... 작업 ...
git status && git diff               # 올리기 전 확인
git add <파일>
git commit -m "feat(영역): 무엇을 했는지"
git push origin <내 브랜치>
```

이후 GitHub에서 **Compare & pull request** → 템플릿 작성 → 박덕현 또는 조성빈의 승인 → **Merge**.
병합 후 **브랜치를 삭제하지 마세요** (상시 브랜치).

## 꼭 지킬 규칙

1. 작업은 **내 브랜치**에서. 내 브랜치 안에서는 push 자유.
2. `main`에는 **직접 push 불가**. 항상 PR을 통해 병합.
3. 며칠 단위로 자주 PR을 올리고, 다른 브랜치가 병합되면 `git pull origin main`으로 내 브랜치를 최신화.
4. 브랜치 전환은 `git checkout`이 아니라 **`git switch`**. (`frontend`/`backend`/`infra`는 폴더 이름과 같아 checkout이 오류를 냄)
5. **비밀값(API 키, 비밀번호, 서버 주소)과 실제 닉네임은 절대 커밋 금지.** 이 레포는 Public입니다.

## 자주 나는 오류

| 메시지 | 해결 |
|---|---|
| `'infra' could be both a local file and a tracking branch` | `git checkout` 대신 `git switch <브랜치>` |
| `GH006` / `GH013` — Changes must be made through a pull request | main에 직접 push한 것. 내 브랜치에서 PR로 |
| `403` / `Permission denied` | 초대 수락 여부, 로그인 계정 확인 |
| `LF will be replaced by CRLF` 경고 | 무시해도 됨 |

자세한 설명과 그 외 오류 해결법은 **Finesse GitHub 사용 가이드**(팀 공유 문서)를 참고하세요. 협업 규칙 전문은 [CONTRIBUTING.md](CONTRIBUTING.md).
