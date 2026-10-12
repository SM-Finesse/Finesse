# deploy 브랜치 (배포 목표 전용 — 코드 없음)

학교 서버 PC가 이 브랜치의 `target.env` 를 2분마다 읽어 그 태그의 이미지로 맞춥니다.
자세한 내용: main 브랜치 `infra/deploy/README.md`

- **자동 갱신**: main 병합 → `docker-publish.yml` 이 이미지 푸시 성공 후 `TAG=<커밋 7자리>` 커밋
- **배포 이력**: 이 브랜치의 커밋 이력
- **롤백**: GitHub 웹에서 `target.env` 의 TAG 를 이전 값으로 수정 (다음 main 병합 때 덮어써짐)
- 이 브랜치는 main 에 병합하지 않습니다
