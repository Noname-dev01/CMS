#!/usr/bin/env bash
# CI 전용 공통 함수 — prod-smoke.sh·prod-backup-restore-roundtrip.sh가 source한다.
# 계획: adversarial-review/plan/PLAN-ci-prod-gates.md (M-06)
#
# ⚠️ 이 스크립트들은 고정 이름의 prod 컨테이너(cms-*-prod)·볼륨(cms_*_prod)·.env.prod를
# 직접 만들고 지운다. compose 프로젝트명·컨테이너명·볼륨명이 고정이라 다른 디렉터리에서
# 실행해도 격리되지 않는다 — 폐기 가능한 Docker 환경(CI 러너)에서만 실행한다.
# 로컬에서는 CMS_CI_DISPOSABLE_DOCKER=1을 명시해야 시작하며, 기존 자원이 하나라도 있으면
# 어떤 변경도 하기 전에 중단한다.

CI_APP_CONTAINER="cms-app-prod"
CI_DB_CONTAINER="cms-db-prod"
CI_DB_VOLUME="cms_db_data_prod"
CI_FILES_VOLUME="cms_notice_attachments_prod"
CI_BASE_URL="http://127.0.0.1:8080"
CI_COMPOSE=(docker compose -f docker-compose.prod.yml --env-file .env.prod)

# 합성 값이다 — 실 시크릿이 아니며 러너 임시 파일(.env.prod, .gitignore 대상)에만 존재한다.
CI_DB_NAME="cms_ci"
CI_ADMIN_ID="ciadmin"
CI_ADMIN_PW="CiSmokeAdmin-Password-2026"

ci_fail() {
  echo "❌ [CI 게이트 실패] $*" >&2
  exit 1
}

ci_ok() {
  echo "✅ $*"
}

# repo 루트에서 실행한다(docker-compose.prod.yml·.env.prod 상대 경로 전제).
ci_enter_repo_root() {
  cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
}

# 이번 실행이 자원을 만들었는지 — 잠금 획득 + preflight 통과 후에만 정리 대상이 된다.
CI_OWNS_STACK=0
CI_TMP_DIR=""

# 같은 Docker 데몬을 쓰는 모든 체크아웃이 공유하는 배타 잠금. 부재 검사(preflight)와 생성 사이에 다른
# 실행이 끼어들어 서로의 고정 이름 스택을 지우는 경합(codex 리뷰 지적)을 막는다.
# 잠금은 "이름이 고정된 컨테이너"다 — 컨테이너 이름은 데몬이 예약 시점에 유일성을 강제한다(같은 이름의
# 두 번째 create는 Conflict). 네트워크 이름은 Docker 문서상 중복 검출이 보장되지 않아 잠금으로 쓰지
# 않는다. 컨테이너는 만들기만 하고 기동하지 않는다. 라벨의 무작위 토큰은 재사용 경로가 "부모 실행이 만든
# 스택"임을 증명하는 데도 쓴다(ci_require_parent_ownership).
# 비정상 종료(SIGKILL 등)로 잠금이 남으면 자동으로 빼앗지 않는다 — 진행 중인 실행이 없음을 사람이 확인한
# 뒤 `docker rm cms-ci-prod-lock`으로 지운다.
CI_LOCK_NAME="cms-ci-prod-lock"
CI_HOLDS_LOCK=0

# 시험용 컨테이너에 쓰는 mariadb 이미지 — prod-backup.sh가 쓰는 고정 참조를 그대로 재사용한다.
ci_mariadb_image() {
  local img
  img=$(grep -oE 'mariadb:10\.11@sha256:[0-9a-f]{64}' scripts/prod-backup.sh | head -1)
  [ -n "$img" ] || ci_fail "prod-backup.sh에서 고정된 mariadb 이미지 참조를 찾지 못했습니다."
  echo "$img"
}

ci_require_disposable() {
  if [ "${CI:-}" != "true" ] && [ "${CMS_CI_DISPOSABLE_DOCKER:-}" != "1" ]; then
    ci_fail "폐기 가능한 Docker 환경에서만 실행합니다. 로컬에서는 CMS_CI_DISPOSABLE_DOCKER=1을 명시하세요(기존 prod 컨테이너·볼륨이 있으면 어차피 중단됩니다)."
  fi
}

ci_acquire_lock() {
  local token="$(date +%s)-$$-$RANDOM$RANDOM" img
  img=$(ci_mariadb_image)
  # 이미지 pull 실패 등 이름 충돌이 아닌 실패와 구분하기 위해 오류 출력을 보관한다.
  if ! err=$(docker create --name "$CI_LOCK_NAME" --label "cms.ci.run=$token" "$img" true 2>&1 >/dev/null); then
    if docker inspect "$CI_LOCK_NAME" >/dev/null 2>&1; then
      ci_fail "다른 CI 실행이 진행 중이거나 이전 실행이 남긴 잠금($CI_LOCK_NAME)이 있습니다. 실행 중인 것이 없음을 확인한 뒤에만 'docker rm $CI_LOCK_NAME'으로 지우세요."
    fi
    ci_fail "잠금 컨테이너를 만들지 못했습니다: $err"
  fi
  CI_HOLDS_LOCK=1
  export CMS_CI_RUN_TOKEN="$token"
}

# 부모 실행(prod-smoke.sh)이 띄운 스택을 재사용하는 경로의 진입 검증 — 환경변수만으로 기존(타인의)
# prod 스택에 쓰지 못하게, 잠금 네트워크 라벨의 토큰과 합성 DB 이름을 쓰기 전에 대조한다.
ci_require_parent_ownership() {
  ci_require_disposable
  [ -n "${CMS_CI_RUN_TOKEN:-}" ] || ci_fail "재사용 모드에는 부모 실행의 CMS_CI_RUN_TOKEN이 필요합니다."
  local label db
  label=$(docker inspect "$CI_LOCK_NAME" --format '{{index .Config.Labels "cms.ci.run"}}' 2>/dev/null) \
    || ci_fail "재사용 모드: 부모 실행의 잠금($CI_LOCK_NAME)이 없습니다."
  [ "$label" = "$CMS_CI_RUN_TOKEN" ] || ci_fail "재사용 모드: 실행 토큰이 잠금과 일치하지 않습니다 — 부모 실행이 아닙니다."
  db=$(docker exec "$CI_DB_CONTAINER" printenv MYSQL_DATABASE 2>/dev/null) \
    || ci_fail "재사용 모드: $CI_DB_CONTAINER 를 찾지 못했습니다."
  [ "$db" = "$CI_DB_NAME" ] || ci_fail "재사용 모드: DB 이름이 합성 값($CI_DB_NAME)이 아닙니다 — 타인의 스택으로 보여 중단합니다."
}

ci_preflight() {
  [ ! -e .env.prod ] || ci_fail "preflight: .env.prod가 이미 있습니다 — 덮어쓰지 않고 중단합니다."

  local names
  names=$(docker ps -a --format '{{.Names}}')
  for c in "$CI_APP_CONTAINER" "$CI_DB_CONTAINER"; do
    if grep -qx "$c" <<<"$names"; then
      ci_fail "preflight: 컨테이너 $c 가 이미 있습니다(정지 포함) — 중단합니다."
    fi
  done

  local vols
  vols=$(docker volume ls --format '{{.Name}}')
  for v in "$CI_DB_VOLUME" "$CI_FILES_VOLUME"; do
    if grep -qx "$v" <<<"$vols"; then
      ci_fail "preflight: 볼륨 $v 가 이미 있습니다 — 중단합니다."
    fi
  done

  # 127.0.0.1:8080 점유 여부 — /dev/tcp 연결이 성공하면 누군가 쓰고 있는 것이다.
  if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
    ci_fail "preflight: 127.0.0.1:8080이 이미 사용 중입니다 — 중단합니다."
  fi
}

ci_write_env() {
  # 시크릿 값은 명령줄에 넣지 않는다 — 파일로만 쓴다.
  cat > .env.prod <<EOF
MYSQL_ROOT_PASSWORD=ci-root-pw-not-a-secret
MYSQL_DATABASE=$CI_DB_NAME
MYSQL_USER=ci_user
MYSQL_PASSWORD=ci-user-pw-not-a-secret
MAIL_USER=ci@example.com
MAIL_PASS=ci-mail-pw-not-a-secret
APP_BASE_URL=$CI_BASE_URL
ADMIN_BOOTSTRAP_USER_ID=$CI_ADMIN_ID
ADMIN_BOOTSTRAP_PASSWORD=$CI_ADMIN_PW
ADMIN_BOOTSTRAP_EMAIL=ci-admin@example.com
EOF
}

# 이번 실행이 만든 자원만 정리한다. preflight 통과 전(CI_OWNS_STACK=0)에는 아무것도 지우지 않는다.
ci_cleanup() {
  local code=$?
  local cleanup_failed=0
  if [ "$CI_OWNS_STACK" = "1" ]; then
    if [ "$code" -ne 0 ]; then
      echo "--- 실패 진단: 앱/DB 로그 ---" >&2
      "${CI_COMPOSE[@]}" logs --no-color --tail 200 >&2 || true
    fi
    # down -v: compose에 선언된 고정 이름 볼륨 2개만 제거한다(preflight가 사전 부재를 증명).
    # 실패를 숨기지 않는다 — 실패하면 스택이 남았다는 뜻이라 종료 코드에 반영하고, 재시도·수동 정리에
    # 필요한 .env.prod와 잠금을 보존한다(다음 실행이 preflight에서 막히는 것이 의도된 안전장치).
    if ! "${CI_COMPOSE[@]}" down -v >/dev/null 2>&1; then
      cleanup_failed=1
      echo "❌ [CI 게이트] 정리(down -v) 실패 — 스택이 남아 있을 수 있어 .env.prod와 잠금($CI_LOCK_NAME)을 보존합니다. 수동으로 'docker compose -f docker-compose.prod.yml --env-file .env.prod down -v' 후 지우세요." >&2
    else
      rm -f .env.prod
      [ -n "$CI_TMP_DIR" ] && rm -rf "$CI_TMP_DIR"
    fi
  fi
  # 잠금은 정리가 성공한 뒤에 푼다 — 정리가 끝나기 전에 다른 실행이 시작되지 않게.
  if [ "$CI_HOLDS_LOCK" = "1" ] && [ "$cleanup_failed" = "0" ]; then
    docker rm "$CI_LOCK_NAME" >/dev/null 2>&1 || true
  fi
  if [ "$cleanup_failed" = "1" ] && [ "$code" -eq 0 ]; then
    code=1
  fi
  return "$code"
}

# 확인장치 → 배타 잠금 → preflight → 합성 env → 실제 prod-up.sh 실행(빌드·기동·health·RestartCount
# 안정 확인은 스크립트 몫). 잠금을 잡은 뒤에야 trap을 걸고, 소유권(OWNS)은 preflight 통과 후에만 선다 —
# preflight에서 실패하면 잠금만 풀고 아무 자원도 지우지 않는다.
ci_start_stack() {
  ci_require_disposable
  # 호스트 셸에 남은 prod 환경변수가 compose 보간을 덮어쓰거나 깨뜨리지 않게 부모 셸에서도 가드를 적용한다
  # (prod-up.sh는 자식 셸에서 자체 가드를 적용하지만, 아래 cleanup의 compose 호출은 이 셸에서 실행된다 —
  # _prod-env-guard.sh 주석: down도 동일 가드가 필요).
  # shellcheck source=../_prod-env-guard.sh
  source "$(dirname "${BASH_SOURCE[0]}")/../_prod-env-guard.sh"
  prod_env_guard_unset_host_vars
  ci_acquire_lock
  trap ci_cleanup EXIT
  ci_preflight
  CI_OWNS_STACK=1
  CI_TMP_DIR=$(mktemp -d)
  ci_write_env
  bash scripts/prod-up.sh
}

ci_wait_health() {
  local deadline=$((SECONDS + 90))
  until curl --fail --silent --connect-timeout 2 --max-time 4 "$CI_BASE_URL/actuator/health" >/dev/null; do
    [ "$SECONDS" -lt "$deadline" ] || ci_fail "90초 내에 /actuator/health가 회복되지 않았습니다."
    sleep 2
  done
}

# HTTP 응답 코드만 반환. 전송 실패는 000이 아니라 호출부가 실패로 다루도록 별도 확인한다.
ci_http_code() {
  local code
  code=$(curl --silent --output /dev/null --write-out '%{http_code}' --connect-timeout 3 --max-time 10 "$@") \
    || ci_fail "curl 전송 실패: $*"
  [ "$code" != "000" ] || ci_fail "curl 전송 실패(000): $*"
  echo "$code"
}
