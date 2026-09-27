# 격리 restore drill 실행 기록 — M-01/H-04 / PR 6

## 범위와 전제

이 문서는 `adversarial-review/remediation-plan.md` PR 6 7절 "격리 restore drill" 절차의 실제 실행 결과를 기록한다. 목표는 (1) quiesced 백업이 실제로 시점 정합성을 보장하는지, (2) `prod-restore.sh`가 복원한 데이터가 DB·파일 양쪽에서 **전체**(표본 아님) 정확한지, (3) 백업/복구/drill 세 `BACKUP_DIR`의 분리가 실제로 서로 간섭하지 않는지를 synthetic fixture로 증명하는 것이다.

**격리 환경**: 이 프로젝트에 실제 배포된 prod가 아직 없어(로드맵 전역에서 실배포는 별도 사용자 결정 사안으로 명시) 로컬 `docker-compose.prod.yml` 스택 자체를 격리 drill 환경으로 썼다(PR 6 v19~v21 설계 결정 참조, "전용 daemon/VM" 요구를 로컬 disposable 스택 + 사전/사후 체크리스트로 대체). 재해복구(빈 볼륨에서 새로 시작)는 `PLAN-db-backup.md`(PR #30)에서 이미 실기 검증됐으므로 이번 drill에서 반복하지 않는다.

## 사전 체크리스트 (drill 시작 전)

- `docker ps -a`(정지 컨테이너 포함) 확인 — 실행 중인 `cms-app-dev`/`cms-db-dev` 외 잔존 `cms-*-prod` 컨테이너 없음(정지 포함).
- `docker volume ls` 확인 — **`cms_db_data_prod`(136.9MB)·`cms_notice_attachments_prod`(40KB) 잔존 발견**(마지막 수정 2026-09-24, 날짜상 PR 2(H-01) 실기 검증 때 생성된 stale 산출물로 판단 — 실 운영 데이터 아님). 자동 재사용·삭제하지 않고 **사용자에게 확인 후** 삭제.
- 호스트 8080 포트 확인 — dev 스택(`cms-app-dev`)이 `0.0.0.0:8080->8080`으로 점유 중이라 prod(`127.0.0.1:8080:8080`)와 충돌 확인 — **사용자 확인 후** `docker stop cms-app-dev`로 임시 정지(dev DB는 유지, 포트 3307 무관).
- `./backups`·`./backups-quiesced`·`./backups-drill` 잔존 확인 — 없음.

## Fixture 구성

- ADMIN 계정: `AdminBootstrapLoader`가 `.env.prod`(drill 전용 synthetic 값, 실 시크릿 아님)의 `ADMIN_BOOTSTRAP_*`로 `drilladmin` 생성.
- 공개 공지(id=1, `useYn=true`) + 첨부 1개(`public-attach.txt`, 61B).
- 비공개 공지(id=2, `useYn=false`) + 첨부 1개(`private-attach.txt`, 62B).
- 관리자 프로필 이미지(`kind=UPLOADED`, 1×1 PNG, 68B).

## 실행 절차 및 결과 (2026-09-27)

| 단계 | 내용 | 결과 |
|---|---|---|
| 1 | 백업 전 DB 참조 목록(storageKey 3건) + 파일 3개 전체 sha256 기록 | `7d574d0d...txt`=`bd1bc360…`, `c8c17e85...txt`=`1226b070…`, `608b5ad4...png`=`431ced69…` — 업로드 직후 볼륨 내 파일과 로컬 원본 해시 일치 확인 |
| 2 | 정규(quiesced) 백업: `docker stop cms-app-prod` → `BACKUP_DIR=./backups-drill bash scripts/prod-backup.sh` → `docker start cms-app-prod` | 성공. 디스크 여유 공간 경고(1009MB, 비차단) 외 정상. `manifest.txt`: `database=cms_drill`, `flyway_max_version=11` |
| 3 | 재기동 후 health 폴링 | 첫 확인 시 `curl: (52) Empty reply`(기동 중) → 재폴링에서 `200 UP` 확인 |
| 4 | fixture 변경(백업 이후) | 공지 1 제목 변경("MUTATED AFTER BACKUP"), 공지 2 첨부 **삭제**(204), 프로필 이미지 **교체** — 3건 모두 API 200/204로 적용 확인 |
| 5 | 손상 checksum 사전 거절 시험 | 백업 사본의 `SHA256SUMS`에 손상 줄 추가 → `prod-restore.sh` 실행 시 "SHA256SUMS 줄 수가 3이 아닙니다"로 **파괴적 변경 전** 즉시 중단. `docker inspect`로 앱 컨테이너 `Running=true` 무변경 확인(원본 유효 백업·운영 컨테이너 모두 영향 없음) |
| 6 | 실제 복구: `BACKUP_DIR=./backups-drill bash scripts/prod-restore.sh ./backups-drill/20260927-192519-9302` (대화형 확인에 `cms_drill` 입력) | 무결성 검증(OK)→이중 DB명 대조 통과→앱 정지→**복구 전 안전 백업이 `./backups-drill/20260927-192636-9704`에 생성됨(정규/보조 경로와 섞이지 않음 — `BACKUP_DIR` export가 내부 안전 백업 호출까지 정확히 전달됨, v20 설계 검증)**→DB/파일 복구→재기동→health/RestartCount 안정 확인. `docker logs`에서 Flyway "Successfully validated 11 migrations" 재확인 |
| 7 | 복구 후 DB 재조회 | 공지 1 제목 **"drill 공개 공지"로 원복**(mutation 소거), 공지 2 첨부 **id=2 다시 존재**(삭제 소거), 프로필 `storage_key`가 원래 `608b5ad4...png`로 원복 — 3건 전부 mutation 이전 상태로 정확히 복원 |
| 8 | 복구 후 **전체**(표본 아님) 파일 대조 | 볼륨 내 파일 목록이 정확히 3개(추가·누락 없음), 각 파일 sha256이 1번 단계 기록과 **바이트 단위로 완전 일치**, UID:GID `10001:10001` 전부 유지 |
| 9 | 애플리케이션 조회(공개/비공개 구분) | 공개 첨부(`GET /notices/1/attachments/1`, 비인증) 200 + 바이트 동일성(`diff` 무차이). 비공개 공지 상세(`GET /notices/2`, 비인증) 404. 비공개 첨부(`GET /notices/2/attachments/2`, 비인증) 404. 비공개 첨부(관리자 인증, `GET /admin/api/notices/2/attachments/2/content`) 200 + 바이트 동일성. 관리자 프로필(`GET /admin/api/members/me/profile-image`, 인증) 200 + sha256 완전 일치 |
| 10 | Gate G 관련 부가 확인 | `RestartCount=0`(크래시 루프 없음), `docker logs`에 AdminBootstrapLoader 오류 없음(적격 ADMIN 이미 존재 상태로 정상 스킵) |

## 잔여 사항 (범위 밖으로 명시)

- **H-01 LOCKED/EXPIRED 계정이 포함된 복원본의 기동 무변경**은 이번 drill의 fixture에 포함하지 않았다 — 해당 시나리오는 PR 2의 `AdminBootstrapStartupIntegrationTest`(8상태 행렬)로 이미 별도 검증돼 있어 중복 검증하지 않는다.
- **재해복구(빈 볼륨에서 새 스택 + 복구)**는 `PLAN-db-backup.md`(PR #30)에서 이미 실기 검증됐다 — 이번 drill은 반복하지 않는다.
- **connection 등 네트워크 계층 fault**(디스크 여유 공간 부족으로 인한 실제 백업 실패 등)는 이번 drill에서 인위적으로 재현하지 않았다 — 디스크 여유 공간 경고만 관찰(1GB 미만이지만 실제로는 성공).

## 정리

`bash scripts/prod-down.sh`(볼륨 보존 옵션으로 컨테이너만 제거) → 이번 drill에서 새로 만든 `cms_db_data_prod`/`cms_notice_attachments_prod` 볼륨만 `docker volume rm`으로 제거(사전 발견된 stale 볼륨과 이번 생성분이 결과적으로 같은 이름이라 별도 구분 불필요 — 어차피 이번 drill 종료 시점에는 전부 drill 전용 데이터만 남아 있었음) → `./backups-drill` 전체 제거(증거는 위 표로 대체 보존) → `docker start cms-app-dev`로 사용자 dev 스택 재기동, `GET /admin/login` 200으로 정상 확인 → drill 전용 `.env.prod`(synthetic 값, 실 시크릿 아님) 삭제.

## 결론

정규(quiesced) 백업·drill 전용 `BACKUP_DIR` 분리·복구 후 전체 파일 검증·애플리케이션 조회 구분까지 이번 drill의 설계 목표를 모두 실기로 확인했다. 이 결과는 이 이미지·fixture·백업 세트에 대한 것이며, 이미지·스키마·스토리지 계약이 바뀌면 drill을 재실행한다(`docs/deployment.md` "복구" 절 참조). 매 실제 운영 복구 후에는 이 drill과 별개로 `docs/deployment.md`의 "복구 후 체크리스트"를 수행해야 한다 — drill 1회가 향후 개별 복구의 정합성까지 보증하지 않는다.
