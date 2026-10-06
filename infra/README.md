# /infra

Docker, docker-compose, k8s 매니페스트. `.github/workflows`(CI)도 이 브랜치에서 관리합니다.

- 담당 브랜치: `infra` (박덕현)

## 구성

- [`mocks/`](./mocks/README.md) — LLM Mock 서버(`llm-mock-1/2`), TETR.IO API Mock(`tetrio-mock`),
  LLM Payload Mock(`llm-payload-mock`). 2026-10-02 `HJ2002-star/DevOps-prototype`에서 동기화.
  실행 방법·테스트 방법은 `mocks/README.md` 참고.
- k8s 매니페스트(k3s/minikube), CI 파이프라인(`.github/workflows`)은 아직 범위 밖 — 다음 단계.
