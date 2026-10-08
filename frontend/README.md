# /frontend

React + Vite + Tailwind.

- 담당 브랜치: `frontend` (호준수)

## 스택

React 19 · TypeScript 6 · Vite 8 · Tailwind CSS 4 · Recharts 3 · Vitest 5 + Testing Library

## 실행

Node **22.12 이상**이 필요합니다 (Vitest 5 · jsdom 30 요구사항).

```bash
cd frontend
npm ci              # 의존성 설치
npm run dev         # 개발 서버 → http://localhost:5173
npm test            # 테스트 1회 실행
npm run test:watch  # 저장할 때마다 테스트
npm run coverage    # 테스트 커버리지
npm run build       # 타입 검사(tsc -b) + 프로덕션 빌드
```

## 구조

```
src/
  pages/        화면 단위 (LandingPage = 유저명 입력, ReportPending = 결과 화면 자리)
  components/   재사용 UI (UsernameForm, ModeCard, InfoPanel, Kbd …)
  hooks/        단축키(useHotkeys)
  i18n/         한국어 · 영어 문구 (FR-13), useContext로 공유
  lib/          유저명 검증(username.ts) 등 순수 로직
  index.css     Tailwind @theme 토큰 — 색 · 글꼴 (Recharts도 var(--color-piece-*) 사용)
```
