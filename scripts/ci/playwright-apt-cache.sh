#!/usr/bin/env bash
# CI browser-test job 전용 — Playwright `install --with-deps`가 apt로 받는 deb(주로 폰트)를 캐시로 재사용한다.
# 계획: adversarial-review/plan/PLAN-ci-playwright-apt-cache.md (이슈 #283)
#
#   stage   : 캐시 디렉터리의 deb를 apt 색인과 대조해, 통과한 것만 apt 보관 디렉터리에 apt가 쓰는 이름으로 넣는다.
#   collect : 설치가 끝난 뒤 보관 디렉터리의 deb 중 지금 설치된 버전만 캐시 디렉터리로 회수한다.
#
# 캐시는 신뢰하지 않는다 — 내용 해시는 apt 색인(서명 확인 후 저장된 것)의 값과 대조하고, 파일 이름은 캐시의 것을
# 쓰지 않고 apt가 알려 주는 이름으로 만든다(apt는 그 이름·크기의 파일이 있으면 다시 받지 않을 수 있다).
# 캐시 문제로는 실패하지 않는다: 파일별 오류는 그 파일만 버리고, 최악의 경우 지금처럼 미러에서 받을 뿐이다.
# 다른 scripts/ci/*.sh와 달리 Docker 자원을 만들지 않는다. apt 보관 디렉터리를 바꾸므로 CI 러너에서만 실행한다.

set -uo pipefail

CACHE_DIR="${APT_CACHE_DIR:-.ci-apt-cache}"
ARCHIVES_DIR="${APT_ARCHIVES_DIR:-/var/cache/apt/archives}"
UPDATE_TIMEOUT="${APT_CACHE_UPDATE_TIMEOUT:-120}"
# root로 도는 시험 컨테이너에서는 SUDO=""로 비운다.
SUDO="${SUDO-sudo}"

log() { echo "[apt-cache] $*"; }

# 단계 출력(GitHub Actions)에 키=값을 남긴다. 로컬 실행에서는 무시한다.
output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi
}

# deb의 Package·Version·Architecture를 "pkg ver arch"로 출력한다. 분석 실패·빈 필드면 실패를 돌려준다.
# dpkg-deb -f는 없는 필드에 오류를 내지 않으므로 빈 값을 따로 검사한다.
read_fields() {
  local fields pkg ver arch
  fields="$(dpkg-deb -f "$1" Package Version Architecture 2>/dev/null)" || return 1
  pkg="$(sed -n 's/^Package: //p' <<<"$fields")"
  ver="$(sed -n 's/^Version: //p' <<<"$fields")"
  arch="$(sed -n 's/^Architecture: //p' <<<"$fields")"
  [ -n "$pkg" ] && [ -n "$ver" ] && [ -n "$arch" ] || return 1
  echo "$pkg $ver $arch"
}

# 패키지의 후보 버전(apt가 지금 설치할 버전)을 출력한다. 없으면 빈 문자열.
candidate_version() {
  apt-cache policy "$1" 2>/dev/null | sed -n 's/^ *Candidate: //p' | grep -v '^(none)$' || true
}

# 후보 버전 기록의 "파일이름 해시"를 출력한다(apt-get download --print-uris — apt가 보관 디렉터리에 쓰는 이름과
# 색인의 해시. 예: fonts-x_1%3a2.0_all.deb SHA256:ab12…). 없으면 빈 문자열.
candidate_record() {
  apt-get download --print-uris "$1" 2>/dev/null | awk 'NR==1 { print $2, $4 }'
}

# "ALGO:HEX" 해시를 파일과 대조한다(색인이 주는 가장 강한 해시를 그대로 쓴다).
hash_matches() {
  local file="$1" expected="$2" algo hex actual
  algo="${expected%%:*}"
  hex="${expected#*:}"
  case "$algo" in
    SHA256) actual="$(sha256sum "$file" | cut -d' ' -f1)" ;;
    SHA512) actual="$(sha512sum "$file" | cut -d' ' -f1)" ;;
    *) return 1 ;;
  esac
  [ -n "$hex" ] && [ "$actual" = "$hex" ]
}

stage() {
  shopt -s nullglob
  local debs=("$CACHE_DIR"/*.deb)
  if [ ${#debs[@]} -eq 0 ]; then
    log "복원된 deb 없음 — 투입하지 않는다(미러에서 받는다)"
    output staged 0
    return 0
  fi
  log "복원된 deb ${#debs[@]}개 — apt 색인 갱신(제한 ${UPDATE_TIMEOUT}초)"
  if ! $SUDO timeout "$UPDATE_TIMEOUT" apt-get update -qq; then
    log "⚠️ apt-get update 실패 또는 시간 초과 — 투입을 포기한다(미러에서 받는다)"
    output staged 0
    return 0
  fi

  local staged=0 dropped=0 deb fields pkg ver arch cand record name expected tmp
  for deb in "${debs[@]}"; do
    if ! fields="$(read_fields "$deb")"; then
      log "버림(분석 실패 또는 필드 누락): $(basename "$deb")"; dropped=$((dropped + 1)); continue
    fi
    read -r pkg ver arch <<<"$fields"
    cand="$(candidate_version "$pkg")"
    if [ -z "$cand" ]; then
      log "버림(후보 없음): $pkg"; dropped=$((dropped + 1)); continue
    fi
    if [ "$ver" != "$cand" ]; then
      log "버림(후보 버전 아님 $ver ≠ $cand): $pkg"; dropped=$((dropped + 1)); continue
    fi
    record="$(candidate_record "$pkg")"
    name="${record%% *}"
    expected="${record#* }"
    if [ -z "$record" ] || [ "$name" = "$record" ] || [ -z "$expected" ]; then
      log "버림(색인 해시 없음): $pkg"; dropped=$((dropped + 1)); continue
    fi
    if ! hash_matches "$deb" "$expected"; then
      log "버림(해시 불일치): $pkg $ver"; dropped=$((dropped + 1)); continue
    fi
    # 임시 이름으로 복사한 뒤 옮긴다 — 단계가 중간에 끊겨도 apt가 찾는 이름의 반쪽 파일이 남지 않게.
    tmp="$ARCHIVES_DIR/.apt-cache-stage.$$"
    if $SUDO cp "$deb" "$tmp" && $SUDO mv -f "$tmp" "$ARCHIVES_DIR/$name"; then
      log "투입: $name ($arch)"; staged=$((staged + 1))
    else
      $SUDO rm -f "$tmp"; log "버림(복사 실패): $pkg"; dropped=$((dropped + 1))
    fi
  done
  log "투입 ${staged}개, 버림 ${dropped}개"
  output staged "$staged"
}

collect() {
  shopt -s nullglob
  rm -rf "$CACHE_DIR"
  mkdir -p "$CACHE_DIR"
  local collected=0 deb fields pkg ver arch installed record name
  for deb in "$ARCHIVES_DIR"/*.deb; do
    fields="$(read_fields "$deb")" || continue
    read -r pkg ver arch <<<"$fields"
    installed="$(dpkg-query -W -f='${Version}' "$pkg" 2>/dev/null || true)"
    [ "$installed" = "$ver" ] || continue
    # 다음 실행의 stage가 넣을 이름과 apt가 실제로 쓴 이름이 같아야 투입이 효과가 있다.
    record="$(candidate_record "$pkg")"
    name="${record%% *}"
    if [ -n "$record" ] && [ "$name" != "$(basename "$deb")" ]; then
      log "⚠️ 이름 규칙 불일치: apt 파일 $(basename "$deb"), 색인 이름 $name"
    fi
    cp "$deb" "$CACHE_DIR/" && collected=$((collected + 1))
  done
  log "회수 ${collected}개 ($(du -sh "$CACHE_DIR" | cut -f1))"
  output collected "$collected"
}

case "${1:-}" in
  stage) stage ;;
  collect) collect ;;
  *) echo "사용법: $0 stage|collect" >&2; exit 2 ;;
esac
