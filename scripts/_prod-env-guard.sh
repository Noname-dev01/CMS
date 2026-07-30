# Docker Compose는 호스트 셸에 이미 설정된 환경변수를 --env-file보다 우선 적용한다.
# .env.prod만이 이 변수들의 유일한 입력이 되도록, 셸에 남아있으면 경고(값은 출력 안 함) 후
# unset하고 진행한다(PLAN-prod-profile.md 결정 7·8, v5·v6). prod-up.sh·prod-down.sh 공용 —
# down도 compose 파일 파싱 시점에 동일한 ${VAR:?} 보간을 거치므로 동일 가드가 필요하다.
PROD_ENV_GUARD_VARS=(
  MYSQL_ROOT_PASSWORD
  MYSQL_DATABASE
  MYSQL_USER
  MYSQL_PASSWORD
  MAIL_USER
  MAIL_PASS
  APP_BASE_URL
  ADMIN_BOOTSTRAP_USER_ID
  ADMIN_BOOTSTRAP_PASSWORD
  ADMIN_BOOTSTRAP_EMAIL
)

prod_env_guard_unset_host_vars() {
  for var in "${PROD_ENV_GUARD_VARS[@]}"; do
    # "${!var+x}"는 값의 길이가 아니라 변수의 정의 여부만 검사한다 — 빈 문자열로
    # export된 변수(예: export MYSQL_PASSWORD=)도 정의된 것으로 간주해 unset해야
    # .env.prod의 유효한 값이 가려지지 않는다.
    if [ -n "${!var+x}" ]; then
      echo "⚠️  호스트 셸에 $var 이(가) 이미 설정돼 있습니다 — .env.prod 값만 쓰도록 unset합니다."
      unset "$var"
    fi
  done
}
