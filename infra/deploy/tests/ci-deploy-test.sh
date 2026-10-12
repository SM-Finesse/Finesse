#!/usr/bin/env bash
# =====================================================================
# deploy.ps1 시나리오 시험 (CI: ci.yml 의 cd-deploy job 에서 실행)
# 실제 DockerHub 이미지로 backend+frontend 를 띄워
#   ① 첫 배포  ② 변경 없음  ③ 없는 태그(pull 실패)  ④ 같은 실패 태그 재시도 안 함
#   ⑤ 정상 확인 실패 → 자동 롤백  ⑥ 새 태그로 정상 교체(-Force)
# 를 확인. 사용 태그: ec4e96e(main 첫 정식 푸시), infra-6dfe434(시험 푸시) — 둘 다 DockerHub 에 있음
# =====================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

A=ec4e96e
B=infra-6dfe434
T="${RUNNER_TEMP:-/tmp}/target.env"
export TARGET_URL="$T" HEALTH_TIMEOUT_SEC=120

cat > .env <<ENV
COMPOSE_PROFILES=backend,frontend
FRONTEND_PORT=8088
LLM_SERVERS=http://127.0.0.1:9
ENV
rm -rf state logs

fail=0
passed=()
run() {  # run <기대 종료코드> <설명> [deploy.ps1 인자]
  local want=$1 desc=$2; shift 2
  echo; echo "=================== $desc"
  pwsh -NoProfile -File ./deploy.ps1 "$@"; local rc=$?
  if [ "$rc" = "$want" ]; then echo "  ✔ 종료코드 $rc"; passed+=("${desc%% *}"); else echo "::error::$desc — 종료코드 $rc (기대 $want)"; fail=1; fi
}
expect_state() {  # expect_state <파일> <기대값>
  local got; got=$(tr -d '[:space:]' < "state/$1" 2>/dev/null || true)
  if [ "$got" = "$2" ]; then echo "  ✔ state/$1 = '$2'"; else echo "::error::state/$1 = '$got' (기대 '$2')"; fail=1; fi
}
expect_running() {  # 실제로 떠 있는 컨테이너 이미지 태그
  for s in backend frontend; do
    local img; img=$(docker inspect -f '{{.Config.Image}}' "finesse-$s-1" 2>/dev/null || echo none)
    if [ "$img" = "pdh20020317/finesse-$s:$1" ]; then echo "  ✔ $s 실행 중: $img"; else echo "::error::$s 실행 이미지 $img (기대 :$1)"; fail=1; fi
  done
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/)
  [ "$code" = 200 ] && echo "  ✔ frontend 200" || { echo "::error::frontend 응답 $code"; fail=1; }
}

echo "TAG=$A" > "$T"
run 0 "① 첫 배포 ($A)";                       expect_state current_tag $A; expect_running $A
run 0 "② 변경 없음";                            expect_state current_tag $A

echo "TAG=no-such-tag-ci" > "$T"
run 2 "③ 없는 태그 → pull 실패, 기존 유지";       expect_state current_tag $A; expect_state failed_tag no-such-tag-ci; expect_running $A
run 0 "④ 같은 실패 태그 → 재시도 안 함";          expect_state current_tag $A

echo "TAG=$B" > "$T"
export DEPLOY_TEST_FAIL_TAG=$B
run 3 "⑤ 정상 확인 실패 → 롤백";
unset DEPLOY_TEST_FAIL_TAG; expect_state current_tag $A; expect_state failed_tag $B; expect_running $A

run 0 "⑥ 새 태그 정상 교체 (-Force)" -Force;   expect_state current_tag $B; expect_state previous_tag $A; expect_running $B

echo; echo "=================== logs/deploy.log"; cat logs/deploy.log
# 로그 파일은 API로 못 받으므로 결과 요약을 주석(annotation)으로 남김 — "초록불 = 실제로 실행됨" 확인용
summary=$(grep -E '배포 시작|\[성공\]|\[실패\]|\[롤백' logs/deploy.log | sed 's/^[0-9-]* [0-9:]* //' | tr '\n' ' / ')
echo "::notice title=배포 시나리오 ${#passed[@]}/6 종료코드 일치 (상태·실행 이미지 검사 실패 ${fail})::${summary}"
docker compose --project-directory . -f docker-compose.yml down -v >/dev/null 2>&1 || true
rm -f .env
exit $fail
