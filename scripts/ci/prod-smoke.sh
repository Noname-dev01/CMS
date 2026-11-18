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
# ADMIN은 2단계 인증이 필수다(이슈 #256, PLAN-totp-required-admin.md) — 부트스트랩 ADMIN의 첫 로그인은 강제 등록 상태다.
# 강제 등록 상태의 차단을 확인한 뒤 등록 → 다시 로그인 → 코드까지 마친 완전 권한 ADMIN으로 기존 점검을 한다.

# RFC 6238 TOTP(SHA1·30초·6자리) — 서버(TotpCodeVerifier)와 같은 계산을 bash·openssl·coreutils로. 컨테이너와 같은 호스트 시계를 쓴다.
# $1 Base32 비밀값, $2 스텝 오프셋(기본 0), $3 기준 시각(epoch 초, 기본 지금)
totp_code() {
  local secret="$1" offset="${2:-0}" now="${3:-$(date +%s)}" key msg mac off bin i
  key=$(printf '%s' "$secret" | base32 -d | od -An -v -tx1 | tr -d ' \n')
  msg=$(printf '%016x' $(( now / 30 + offset )))
  mac=$(for (( i = 0; i < 16; i += 2 )); do printf "\x${msg:i:2}"; done \
    | openssl dgst -sha1 -mac HMAC -macopt "hexkey:$key" -binary | od -An -v -tx1 | tr -d ' \n')
  off=$(( 0x${mac:39:1} ))
  bin=$(( (0x${mac:$((off * 2)):8} & 0x7fffffff) % 1000000 ))
  printf '%06d' "$bin"
}
# RFC 6238 부록 B 시험 벡터(SHA1, 시각 59 → 94287082의 뒤 6자리)
[ "$(totp_code GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ 0 59)" = "287082" ] || ci_fail "TOTP 계산이 RFC 6238 시험 벡터와 다릅니다."

# 응답 HTML에서 _csrf 토큰 값을 꺼낸다(속성 순서 무관).
csrf_of() {
  grep -oE 'name="_csrf"[^>]*value="[^"]+"|value="[^"]+"[^>]*name="_csrf"' <<<"$1" \
    | head -1 | grep -oE 'value="[^"]+"' | head -1 | sed -E 's/value="([^"]+)"/\1/'
}

# 새 쿠키 저장소($1)로 비밀번호 로그인하고 Location 줄을 돌려준다(302가 아니면 실패).
password_login() {
  local jar="$1" page csrf headers
  page=$(curl --silent --fail --cookie-jar "$jar" --connect-timeout 3 --max-time 10 "$CI_BASE_URL/admin/login") \
    || ci_fail "로그인 페이지 조회 실패"
  csrf=$(csrf_of "$page")
  [ -n "$csrf" ] || ci_fail "로그인 폼에서 _csrf 토큰을 찾지 못했습니다."
  headers=$(curl --silent --output /dev/null --dump-header - --cookie "$jar" --cookie-jar "$jar" \
    --connect-timeout 3 --max-time 10 \
    --data-urlencode "username=$CI_ADMIN_ID" --data-urlencode "password=$CI_ADMIN_PW" \
    --data-urlencode "_csrf=$csrf" "$CI_BASE_URL/admin/login") || ci_fail "로그인 요청 전송 실패"
  grep -qE '^HTTP/[0-9.]+ 302' <<<"$(head -1 <<<"$headers")" || ci_fail "로그인 응답이 302가 아닙니다."
  grep -i '^location:' <<<"$headers" | head -1 | tr -d '\r' || true
}

# 302이고 Location이 $3로 끝나야 통과한다.
expect_redirect() {
  local jar="$1" url="$2" path="$3" headers
  headers=$(curl --silent --output /dev/null --dump-header - --cookie "$jar" --connect-timeout 3 --max-time 10 "$url") \
    || ci_fail "curl 전송 실패: $url"
  grep -qE '^HTTP/[0-9.]+ 302' <<<"$(head -1 <<<"$headers")" \
    || ci_fail "$url 응답이 302가 아닙니다: $(head -1 <<<"$headers" | tr -d '\r')"
  grep -i '^location:' <<<"$headers" | head -1 | tr -d '\r' | grep -qE "${path}\$" \
    || ci_fail "$url 의 Location이 $path 가 아닙니다."
}

enroll_jar="$CI_TMP_DIR/cookies-enroll.txt"
login_location=$(password_login "$enroll_jar")
grep -qE '/admin/login/totp-enrollment$' <<<"$login_location" \
  || ci_fail "2단계 미등록 합성 ADMIN이 강제 등록 화면으로 가지 않았습니다: $login_location"
ci_ok "합성 ADMIN 비밀번호 로그인 → 2단계 강제 등록 화면"

# 강제 등록 상태는 관리 화면·actuator 모두 등록 화면으로 302, API는 401이다(완전 권한이 없다).
expect_redirect "$enroll_jar" "$CI_BASE_URL/admin" "/admin/login/totp-enrollment"
expect_redirect "$enroll_jar" "$CI_BASE_URL/actuator/env" "/admin/login/totp-enrollment"
[ "$(ci_http_code --cookie "$enroll_jar" "$CI_BASE_URL/admin/api/members/me")" = "401" ] \
  || ci_fail "강제 등록 상태의 /admin/api/members/me 응답이 401이 아닙니다."
ci_ok "강제 등록 상태: /admin·/actuator/env → 등록 화면 302, /admin/api/members/me 401"

enroll_page=$(curl --silent --fail --cookie "$enroll_jar" --cookie-jar "$enroll_jar" --connect-timeout 3 --max-time 10 \
  "$CI_BASE_URL/admin/login/totp-enrollment") || ci_fail "강제 등록 화면 조회 실패"
totp_secret=$(grep -oE 'id="enrollmentSecret"[^>]*>[A-Z2-7]+<' <<<"$enroll_page" | grep -oE '>[A-Z2-7]+<' | tr -d '<>')
enrollment_id=$(grep -oE 'name="enrollmentId"[^>]*value="[^"]+"' <<<"$enroll_page" | grep -oE 'value="[^"]+"' \
  | sed -E 's/value="([^"]+)"/\1/')
enroll_csrf=$(csrf_of "$enroll_page")
[ -n "$totp_secret" ] && [ -n "$enrollment_id" ] && [ -n "$enroll_csrf" ] \
  || ci_fail "강제 등록 화면에서 설정 키·enrollmentId·_csrf를 찾지 못했습니다."
done_page=$(curl --silent --fail --cookie "$enroll_jar" --cookie-jar "$enroll_jar" --connect-timeout 3 --max-time 10 \
  --data-urlencode "enrollmentId=$enrollment_id" --data-urlencode "code=$(totp_code "$totp_secret")" \
  --data-urlencode "currentPassword=$CI_ADMIN_PW" --data-urlencode "_csrf=$enroll_csrf" \
  "$CI_BASE_URL/admin/login/totp-enrollment") || ci_fail "강제 등록 확정 요청 실패"
grep -q 'id="recoveryCodeList"' <<<"$done_page" || ci_fail "강제 등록 확정 응답이 완료 화면(복구 코드)이 아닙니다."
ci_ok "합성 ADMIN 2단계 등록 완료(복구 코드 화면)"

# 등록은 본인 세션을 끝낸다 — 다시 로그인하면 코드 화면이다. 확정에 이번 스텝을 썼으므로 다음 스텝 코드(허용 범위 +1)를 낸다.
jar="$CI_TMP_DIR/cookies.txt"
login_location=$(password_login "$jar")
grep -qE '/admin/login/totp$' <<<"$login_location" \
  || ci_fail "등록 뒤 로그인이 2단계 코드 화면으로 가지 않았습니다: $login_location"
code_page=$(curl --silent --fail --cookie "$jar" --cookie-jar "$jar" --connect-timeout 3 --max-time 10 \
  "$CI_BASE_URL/admin/login/totp") || ci_fail "2단계 코드 화면 조회 실패"
code_headers=$(curl --silent --output /dev/null --dump-header - --cookie "$jar" --cookie-jar "$jar" \
  --connect-timeout 3 --max-time 10 \
  --data-urlencode "code=$(totp_code "$totp_secret" 1)" --data-urlencode "_csrf=$(csrf_of "$code_page")" \
  "$CI_BASE_URL/admin/login/totp") || ci_fail "2단계 코드 제출 실패"
grep -i '^location:' <<<"$code_headers" | head -1 | tr -d '\r' | grep -qE '/admin$' \
  || ci_fail "2단계 코드 제출 뒤 /admin으로 가지 않았습니다: $(grep -i '^location:' <<<"$code_headers" | tr -d '\r')"
ci_ok "합성 ADMIN 로그인 성공(비밀번호 + 2단계 코드)"

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
