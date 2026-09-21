# 협업 규칙

## 1. 내 브랜치에서 작업
- 각자 자기 상시 브랜치(`frontend` / `backend` / `data-eng` / `llm` / `infra`)에서 작업합니다.
- 자기 브랜치 안에서는 push 자유 — 리뷰·허가 불필요.
- PM(조성빈)은 상시 브랜치가 없고, `/docs` 수정이 필요할 때만 임시 브랜치로 PR을 올립니다.

## 2. main 병합은 PR로만
- `main`에는 직접 push할 수 없습니다 (Owner/Admin 포함 전원).
- PR 병합 조건: 승인 1건 + Code Owner 리뷰 (박덕현 또는 조성빈 중 한 명).
- **병합은 며칠 단위로 자주** 하세요. 오래 안 합칠수록 충돌이 커집니다. 특히 `backend` / `data-eng`는 같은 `/backend` 폴더를 다루므로 더 자주.
- 병합 후 **브랜치를 삭제하지 마세요** (상시 브랜치입니다).

## 3. main → 내 브랜치 동기화
다른 담당 브랜치가 main에 병합된 뒤에는, 내 브랜치에서도 며칠 단위로 main을 받아옵니다.

```bash
git checkout <내 브랜치>
git pull origin main      # 또는 git fetch origin && git merge origin/main
git push origin <내 브랜치>
```

## 4. 크로스 컴포넌트 변경
여러 담당에 동시에 영향을 주는 변경은, 발견한 사람이 PR을 올려 main에 먼저 반영하고 다른 담당자들에게 "각자 브랜치에 pull 받으세요"라고 알립니다.

## 5. 커밋
- 커밋 작성자 정보(`git config user.name/email`)를 본인 GitHub 계정과 맞춰 주세요. 기여 확인에 사용됩니다.
- 커밋 메시지 예: `feat(frontend): 매치 목록 페이지 추가`, `fix(calc): APM 계산 오류 수정`
