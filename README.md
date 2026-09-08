# Finesse

TETR.IO 유저 전적을 분석해 AI가 근거 기반 코멘트를 제공하는 웹 서비스
(종합프로젝트 수업 과제)

## 현재 상태 (2026-09-09 기준)

⚠️ 이 레포 구조(모노레포)는 QA+DevOps가 시험 구축한 초안입니다.
**2026-09-10 팀 회의에서 최종 확정 예정**이며, 멀티레포로 변경될 수 있습니다.

## 폴더 구조

```
/backend      스프링 부트 (API, 통계 계산, LLM 호출 오케스트레이션)
/frontend     React + Vite + Tailwind
/llm-server   LLM 파인튜닝 · 추론 관련 코드
/infra        Docker, docker-compose, k8s 매니페스트
/docs         프로젝트 문서 (기획/요구사항/기술스택 등)
```

## 브랜치 전략

- `main` — 항상 배포 가능한 상태 유지
- 작업 브랜치는 `feature/<이니셜>-설명` 형식으로 필요할 때 생성, 머지 후 삭제
  (예: `feature/hj-mock-llm-schema-update`)
- 사전에 여러 브랜치를 만들어두지 않음

## 개발 환경

로컬 공통 개발환경(Docker Compose)은 `/infra/docker-compose.yml` 참고.
Mock LLM/TETR.IO API 서버 관련 내용은 별도 저장소
(`HJ2002-star/DevOps-prototype`)에서 우선 검증 중이며, 팀 채택 시 이곳으로 통합 예정.

## 참고

- 이 구조는 학사 프로젝트 규모(팀 5인)에 맞춰 관리 비용을 낮추기 위해
  모노레포로 시작하며, 실제 배포·운영 부담이 커지면 컴포넌트별 레포 분리를
  재검토합니다 (기술스택 결정 문서의 "마이크로서비스 아키텍처 제외" 논리와 동일).
