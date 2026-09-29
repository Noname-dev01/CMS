#!/usr/bin/env bash
# prod 스택 스모크 게이트 — 실제 prod-up.sh로 이미지를 빌드·기동하고 보안 응답을 검증한다.
# 계획: adversarial-review/plan/PLAN-ci-prod-gates.md 쟁점 1 (M-06)
#
# ⚠️ 고정 이름 prod 자원을 만들고 지운다 — 폐기 가능한 Docker 환경 전용(_prod-ci-common.sh 참조).
# 사용: CMS_CI_DISPOSABLE_DOCKER=1 bash scripts/ci/prod-smoke.sh   (CI에서는 CI=true로 자동 허용)
# 스모크 통과 뒤 같은 스택에서 백업·복구 왕복 검증(prod-backup-restore-roundtrip.sh)을 이어서 실행한다.
set -euo pipefail

# shellcheck source=./_prod-ci-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_prod-ci-common.sh"
ci_enter_repo_root

bash scripts/ci/check-image-refs.sh

ci_start_stack   # preflight → 합성 .env.prod → scripts/prod-up.sh (health·RestartCount 안정 확인 포함)

# --- 무인증 검증 -------------------------------------------------------------
[ "$(ci_http_code "$CI_BASE_URL/actuator/health")" = "200" ] || ci_fail "/actuator/health가 200이 아닙니다."
ci_ok "health 200"

[ "$(ci_http_code "$CI_BASE_URL/admin/login")" = "200" ] || ci_fail "/admin/login이 200이 아닙니다."
ci_ok "/admin/login 200"

# Actuator 비공개(대표 3경로): 무인증은 302이고 Location이 /admin/login일 때만 통과한다.
# 200·403·5xx·전송 실패(000)는 전부 실패 — "200이 아님"만으로는 서버 오류도 통과해 버린다.
ACTUATOR_PATHS=(/actuator/env /actuator/beans /actuator/metrics)
for p in "${ACTUATOR_PATHS[@]}"; do
  headers=$(curl --silent --output /dev/null --dump-header - --connect-timeout 3 --max-time 10 "$CI_BASE_URL$p") \
    || ci_fail "curl 전송 실패: $p"
  status_line=$(head -1 <<<"$headers" | tr -d '\r')
  location=$(grep -i '^location:' <<<"$headers" | head -1 | tr -d '\r' || true)
  grep -qE '^HTTP/[0-9.]+ 302' <<<"$status_line" || ci_fail "무인증 $p 응답이 302가 아닙니다: $status_line"
  grep -qE '/admin/login' <<<"$location" || ci_fail "무인증 $p 의 Location이 /admin/login이 아닙니다: $location"
done
ci_ok "무인증 Actuator 대표 3경로(env·beans·metrics) 302 → /admin/login"

# --- 합성 ADMIN 로그인 후 검증 --------------------------------------------------
jar="$CI_TMP_DIR/cookies.txt"
login_page=$(curl --silent --fail --cookie-jar "$jar" --connect-timeout 3 --max-time 10 "$CI_BASE_URL/admin/login") \
  || ci_fail "로그인 페이지 조회 실패"
csrf=$(grep -oE 'name="_csrf"[^>]*value="[^"]+"|value="[^"]+"[^>]*name="_csrf"' <<<"$login_page" \
  | head -1 | grep -oE 'value="[^"]+"' | head -1 | sed -E 's/value="([^"]+)"/\1/')
[ -n "$csrf" ] || ci_fail "로그인 폼에서 _csrf 토큰을 찾지 못했습니다."

login_headers=$(curl --silent --output /dev/null --dump-header - --cookie "$jar" --cookie-jar "$jar" \
  --connect-timeout 3 --max-time 10 \
  --data-urlencode "username=$CI_ADMIN_ID" --data-urlencode "password=$CI_ADMIN_PW" \
  --data-urlencode "_csrf=$csrf" "$CI_BASE_URL/admin/login") || ci_fail "로그인 요청 전송 실패"
login_location=$(grep -i '^location:' <<<"$login_headers" | head -1 | tr -d '\r' || true)
grep -qE '^HTTP/[0-9.]+ 302' <<<"$(head -1 <<<"$login_headers")" || ci_fail "로그인 응답이 302가 아닙니다."
# 실패 시 /admin/login-error로 간다 — 성공 판정은 login-error가 아닌 곳으로의 리다이렉트.
grep -q 'login-error' <<<"$login_location" && ci_fail "합성 ADMIN 로그인이 실패했습니다: $login_location"
ci_ok "합성 ADMIN 로그인 성공 ($login_location)"

for p in "${ACTUATOR_PATHS[@]}"; do
  code=$(ci_http_code --cookie "$jar" "$CI_BASE_URL$p")
  [ "$code" = "403" ] || ci_fail "ADMIN 인증 $p 응답이 403이 아닙니다: $code"
done
ci_ok "ADMIN 인증 Actuator 대표 3경로 403"

# prod는 springdoc이 비활성이라 핸들러가 없다 — ADMIN 인증을 통과한 뒤 404여야 한다.
for p in /swagger-ui.html /v3/api-docs; do
  code=$(ci_http_code --cookie "$jar" "$CI_BASE_URL$p")
  [ "$code" = "404" ] || ci_fail "prod에서 ADMIN 인증 $p 응답이 404가 아닙니다: $code"
done
ci_ok "prod Swagger 비활성(ADMIN 인증 후 /swagger-ui.html·/v3/api-docs 404)"

# --- 같은 스택에서 백업·복구 왕복 ---------------------------------------------
CMS_CI_STACK_UP=1 CMS_CI_TMP_DIR="$CI_TMP_DIR" bash scripts/ci/prod-backup-restore-roundtrip.sh

ci_ok "prod-smoke 전체 통과"
