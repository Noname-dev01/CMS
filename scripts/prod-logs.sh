#!/usr/bin/env bash
set -euo pipefail

# docker compose는 서브커맨드와 무관하게 compose 파일 파싱 시점에 ${VAR:?} 보간을 수행한다 —
# logs도 prod-up.sh·prod-down.sh와 동일하게 호스트 셸의 잔여 환경변수(특히 빈 문자열 export)에
# 막힐 수 있으므로 동일 가드를 적용한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_prod-env-guard.sh
source "$SCRIPT_DIR/_prod-env-guard.sh"
prod_env_guard_unset_host_vars

docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f
