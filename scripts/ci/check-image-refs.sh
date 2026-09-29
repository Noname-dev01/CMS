#!/usr/bin/env bash
# 이미지 참조 일치 검사 — mariadb·eclipse-temurin 이미지 참조가 전부 digest로 고정돼 있고,
# mariadb 참조는 sha256 값이 전부 같은지 확인한다(가변 태그·drift 차단).
# Dependabot이 compose만 갱신하고 스크립트·테스트 리터럴이 남는 불일치를 잡는다.
# 계획: adversarial-review/plan/PLAN-ci-prod-gates.md 쟁점 4 (M-06)
#
# 태그는 비교하지 않는다 — 테스트 리터럴(MariaDbContainerSupport)은 Testcontainers/Spring Boot
# 이름 검증이 tag@digest를 거부해 `mariadb@sha256:...`(태그 없음) 형식을 쓰기 때문이다.
# 대상은 "이미지 참조"(이름 뒤에 :태그 또는 @digest가 붙은 것)뿐이다 — `mariadb -u root`처럼
# 명령어로 쓰인 이름과 주석 줄은 제외한다.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

FILES=(
  Dockerfile
  docker-compose.dev.yml
  docker-compose.prod.yml
  scripts/prod-backup.sh
  scripts/prod-restore.sh
  src/test/java/com/cms/support/MariaDbContainerSupport.java
)
DIGEST_RE='@sha256:[0-9a-f]{64}'
status=0

# 파일:줄:내용 형태로 이름 뒤에 :태그 또는 @digest가 붙은 참조 줄만 출력(주석 줄 제외).
image_ref_lines() {
  grep -nE "(^|[^A-Za-z0-9_./-])$1((:[A-Za-z0-9._-]+)|@sha256:[0-9a-f]{64})" "${FILES[@]}" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|//|\*)' || true
}

mariadb_refs=$(image_ref_lines mariadb)
if [ -z "$mariadb_refs" ]; then
  echo "❌ mariadb 이미지 참조를 하나도 찾지 못했습니다(검사 대상 파일 목록 확인 필요)." >&2
  status=1
else
  echo "mariadb: $(grep -c . <<<"$mariadb_refs")개 참조 검사"
  while IFS= read -r line; do
    grep -qE "$DIGEST_RE" <<<"$line" || { echo "❌ digest 없는 mariadb 참조: $line" >&2; status=1; }
  done <<<"$mariadb_refs"
  distinct=$(grep -oE "$DIGEST_RE" <<<"$mariadb_refs" | sort -u | wc -l)
  if [ "$distinct" -gt 1 ]; then
    echo "❌ mariadb 참조의 digest가 서로 다릅니다(${distinct}종):" >&2
    echo "$mariadb_refs" >&2
    status=1
  fi
fi

# eclipse-temurin: Dockerfile FROM 줄이 전부 digest여야 한다(jdk·jre는 다른 이미지라 digest가 달라도 정상).
temurin_from=$(grep -E '^FROM[[:space:]]+eclipse-temurin' Dockerfile || true)
if [ -z "$temurin_from" ]; then
  echo "❌ Dockerfile에서 eclipse-temurin FROM을 찾지 못했습니다." >&2
  status=1
else
  echo "eclipse-temurin: $(grep -c . <<<"$temurin_from")개 FROM 검사"
  while IFS= read -r line; do
    grep -qE "$DIGEST_RE" <<<"$line" || { echo "❌ digest 없는 FROM: $line" >&2; status=1; }
  done <<<"$temurin_from"
fi

if [ "$status" -ne 0 ]; then
  echo "❌ 이미지 참조 검사 실패" >&2
  exit 1
fi
echo "✅ 이미지 참조 검사 통과"
