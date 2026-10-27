# Flyway 마이그레이션 가이드

> 작성일: 2026-07-10 (Flyway 도입 PR) · 2026-10-21 기준 스키마로 합침(`adversarial-review/plan/PLAN-migration-squash.md`)

## 기본 원칙

- **스키마 변경은 Flyway 마이그레이션 파일로만 한다.** `ddl-auto`는 `validate`로 고정되어 있어 Hibernate가 스키마를 변경하지 않는다.
- 마이그레이션 파일 위치: `src/main/resources/db/migration/`
- 파일명 규칙: `V{버전}__{설명}.sql` (버전은 마지막 버전 +1, 설명은 snake_case)
- 한 번 머지된 마이그레이션 파일은 **수정하지 않는다** (체크섬 불일치로 기동 실패). 수정이 필요하면 새 버전을 추가한다.
  - **유일한 예외 — 실배포 전 기준 스키마로 합치기.** 운영 DB가 없는 동안에는 쌓인 마이그레이션을 기준 스키마로 합칠 수 있다. 2026-10-21에 V1~V58을 합쳤고(아래), **첫 실배포 직전에 그 사이 생긴 V3~Vn을 마지막으로 한 번 더 합친다. 그 뒤로는 예외가 없다.** 합칠 때는 옛 체인과 새 기준 스키마로 만든 DB가 같음을 검증한다(컬럼·인덱스·FK·CHECK·AUTO_INCREMENT·시드 행, `flyway_schema_history` 제외 — 절차는 계획 문서 설계 3).

## 현재 마이그레이션 구성

| 버전 | 파일 | 내용 |
|---|---|---|
| V1 | `V1__baseline_schema.sql` | 기준 스키마 — 테이블 28개·인덱스·FK·CHECK(DDL만). 테이블마다 설계 이유를 주석으로 둔다 |
| V2 | `V2__baseline_seed.sql` | 기준 시드 — 사이트 2행(`id=1 admin`·`id=2 main`)·관리자 사이드바 메뉴·공통 공지 게시판·배너/FAQ 가드 행·이미지 카운터(순수 DML, id 명시) |
| V3 | `V3__drop_legacy_schema.sql` | 옛 스키마 정리(2026-10-22, `PLAN-legacy-schema-removal.md`) — 동결 테이블 `notice`·`notice_attachment`, `banner`·`popup`의 옛 `storage_key`·`content_type`·`file_size`와 유니크, `member.profile_image_kind`의 `LEGACY_INLINE`과 그 인덱스, `content_image.scope_type`의 기본값 `'NOTICE'` |
| V4 | `V4__shrink_profile_image_url.sql` | 프로필 이미지 URL 축소(2026-10-24, `PLAN-profile-image-url-shrink.md`) — `member.profile_image_url` `LONGTEXT` → `varchar(255) DEFAULT NULL`(값은 `FileStorage` 키 최대 58자 또는 프리셋 경로뿐) |
| V5 | `V5__add_post_updater_id.sql` | 게시글 최종 수정자(2026-10-27, `PLAN-board-definition.md` PR 0) — `post.updater_id varchar(100) DEFAULT NULL`(DDL 한 문장, 백필 없음 — NULL = V5 이전 글의 수정자 모름). 기존 행에 영향이 없는 NULL 허용 추가라 업그레이드 경로 시험 없이 `BaselineSchemaTest`가 고정하고, 이전 앱도 그대로 기동한다 |

V4는 DDL 한 문장이고 `STRICT_ALL_TABLES`를 그 문장에 고정했다 — **255자를 넘는 비공백 값이 있으면 오류로 멈추고 아무것도 바뀌지 않는다**(비엄격 모드의 `MODIFY`는 값을 조용히 자른다). 걸리면 그 행을 `Member.resetProfileImage()`처럼 **세 컬럼을 함께** 정리한다 — `UPDATE member SET profile_image_kind = 'NONE', profile_image_url = NULL, profile_image_content_type = NULL WHERE CHAR_LENGTH(profile_image_url) > 255;` — 그 뒤 `flyway repair` → 재기동한다(아래 "DDL 실패 복구"). **한계**: 255자 뒤가 공백뿐인 값은 엄격 모드에서도 오류 없이 255자로 잘린다(MariaDB가 NOTE로만 처리 — 시험으로 확인). 앱 경로로는 공백이 든 값을 만들 수 없어 가드를 두지 않았다. **이전 앱 롤백**: V4가 적용된 DB에서 이전 앱(`@Lob` 매핑)을 띄우면 `ddl-auto: validate`가 `found [varchar], but expecting [longtext (CLOB)]`로 **기동에 실패한다**(실측) — 되돌리려면 먼저 `ALTER TABLE member MODIFY profile_image_url LONGTEXT DEFAULT NULL;`(무손실 확대)을 실행한다. 업그레이드 경로는 `ProfileImageUrlShrinkUpgradeTest`가 고정하며 첫 실배포 직전 마지막 합치기 때 V4도 V1에 흡수되고 이 시험도 함께 지운다.

V3는 순수 DDL이고 **실패할 수 있는 문장(`member` enum 축소)이 맨 앞**이다 — `profile_image_kind = 'LEGACY_INLINE'` 행이 있으면(옛 Base64 프로필 이관 러너가 이관하지 못한 행) 엄격 모드(`SET STATEMENT sql_mode='STRICT_ALL_TABLES'`)에서 그 문장이 오류로 멈추고 **아무것도 바뀌지 않는다**. 그 행을 `NONE`(이미지 없음)으로 정리한 뒤 `flyway repair` → 재기동한다(아래 "DDL 실패 복구"). 나머지 문장은 `IF EXISTS`라 다시 실행해도 막히지 않는다. 업그레이드 경로는 `LegacySchemaRemovalUpgradeTest`가 고정한다 — 첫 실배포 직전 마지막 합치기 때 V3는 V1에 흡수되고 이 시험도 함께 지운다.

V1~V2는 옛 V1~V58(2026-07-10~2026-10-16)을 합친 것이다. 옛 파일과 각 변경의 이유·배포 절차는 git 이력(합치기 직전 커밋 `96baec2`)에 있다. 새로 설치한 결과는 `BaselineSchemaTest`가 고정한다(V3~V5 포함).

## 합치기 후 기존 DB (2026-10-21)

합치기 전에 만든 DB는 이력의 V1 체크섬이 달라 **기동이 실패한다**(`validate` 단계). 실배포 전이라 지킬 데이터가 없으므로 다시 만든다.

- **dev DB** (`cms_db_data_dev`): `make dev-down` 후 `docker volume rm cms_db_data_dev`, 다시 `make dev-db`(또는 `make dev-up`). 첨부·프로필 파일 볼륨(`cms_notice_attachments_dev`)은 DB가 비면 고아 파일만 남으므로 함께 지워도 된다(`docker volume rm cms_notice_attachments_dev`). 초기 관리자는 `TestMemberLoader`가 다시 만든다.
- **prod 검증 볼륨** (`cms_db_data_prod`, 있는 경우): `make prod-down` 후 `docker volume rm cms_db_data_prod`(첨부 볼륨 `cms_notice_attachments_prod`도 같다).
- **기존 백업** (`BACKUP_DIR`, 기본 `./backups`): 옛 체인 DB의 덤프라 새 앱으로 복원하면 같은 이유로 기동이 실패한다. 복원 대상으로 쓰지 않는다.
- CI는 매번 빈 DB라 영향이 없다.

## 시험 정책

- 새 마이그레이션이 **기존 행에 영향을 주면**(기존 테이블에 `NOT NULL`·유니크·CHECK 추가, 컬럼 타입 변경, `UPDATE`·`DELETE`·`INSERT … SELECT` 같은 데이터 변환) 업그레이드 경로 시험을 쓴다 — 직전 버전까지 적용한 스키마에 데이터를 넣고 새 버전을 적용해 결과를 단언한다(`MariaDbContainerSupport`의 컨테이너에 별도 스키마를 만든다).
- 새 테이블 생성이나 시드만 있는 마이그레이션은 새로 설치한 결과로 충분하다 — `BaselineSchemaTest`에 단언을 더하거나 기능 통합 시험으로 고정한다.
- 업그레이드 경로 시험은 실배포 직전 합치기 때 이번처럼 정리된다(대상 DB가 없어진다). 실배포 이후에는 계속 유지한다.

## 환경별 동작

- **빈 DB (CI·신규 환경·재생성한 dev DB)**: 별도 설정 없이 V1부터 전체 실행된다.
- `application.yml`의 `spring.flyway`는 Boot 기본값을 쓴다. 옛 "Flyway 도입 전 기존 DB의 일회성 baseline" 절차는 그런 DB가 없어 합치기와 함께 지웠다 — `baseline-on-migrate`를 켜지 않는다(켜면 이력 없는 비어 있지 않은 DB를 검증 없이 기준 스키마로 간주한다).

## 시드 데이터 정책

- 시드는 V2에 id를 명시해 넣는다. 코드가 의존하는 번호가 있다 — `site` `id=1`(관리자 사이트, `menu.site_id DEFAULT 1`·사이드바 상수)·`id=2`(기본 공개 사이트 `main`).
- 시드의 시각 칸은 `NULL` 또는 DB `NOW()`다(`NOW()`는 `ClockUsageConventionTest` 밖의 알려진 한계 — 루트 `CLAUDE.md`).
- 기본 관리자 계정은 SQL이 아니라 `TestMemberLoader`(dev)·`AdminBootstrapLoader`(prod)가 담당한다 — BCrypt 해시를 런타임에 생성해야 하기 때문.
- 가드 행(`banner_lock`·`faq_lock`)과 카운터 행(`content_image_usage`)은 앱이 `FOR UPDATE`로 잠그는 대상이라 반드시 있어야 한다. 새 가드 행을 만드는 기능은 시드도 함께 넣는다.

## 새 마이그레이션 작성 시 주의

- **DML 마이그레이션에 DDL을 섞지 않는다.** MariaDB에서 DDL은 암묵적 커밋을 유발해 실패 시 롤백 보장이 깨진다.
- 시드/백필류는 항상 멱등하게 작성한다 (`WHERE NOT EXISTS`, `WHERE ... IS NULL`, 세션 변수 가드 등). 컬럼·FK 추가 DDL도 가능하면 `IF NOT EXISTS`로 써서 실패 뒤 재실행이 막히지 않게 한다.
- 여러 행을 조건부로 INSERT할 때 행 단위 `(SELECT COUNT(*) ...) = 0` 조건은 첫 INSERT 직후 거짓이 되는 함정이 있다 — 선두에서 세션 변수로 초기 상태를 캡처한다.
- 부모-자식 FK성 참조는 `LAST_INSERT_ID()`로 실제 생성 ID를 캡처한다. AUTO_INCREMENT 시작값을 가정하지 않는다. 같은 연결에 이전 INSERT ID가 남아 있을 수 있으므로, 조건부 INSERT가 건너뛰어진 경우 그 값을 쓰지 않게 가드한다.
- 문자열 정확 일치가 필요한 비교(관리자가 바꿨을 수 있는 시드 이름·URL 판정 등)는 `COLLATE utf8mb4_nopad_bin`을 쓴다 — 테이블 기본 `utf8mb4_general_ci`는 대소문자와 후행 공백을 무시한다.
- 데이터를 되돌릴 수 없게 바꾸는 마이그레이션(컬럼·테이블 삭제, 데이터 변환)은 배포 전 백업 절차를 `docs/deployment.md`에 함께 적는다(실배포 이후).

### DDL 실패 복구 (일반 절차)

MariaDB DDL은 문마다 암묵 커밋이라, 파일에 DDL이 여러 개면 중간에 끊겼을 때 일부 객체만 남는다. DDL이 하나여도 **DDL 커밋 후 이력 기록 전에 프로세스가 끊기면** "객체는 있고 성공 이력은 없는" 상태나 `success=0` 이력이 남는다.

1. 앱 인스턴스를 정지하고 실패 원인을 없앤다.
2. 상태를 확인한다 — `SELECT version, success FROM flyway_schema_history WHERE version = 'N';`, 그 버전이 만드는 객체의 존재(`SHOW TABLES LIKE …`, `SHOW COLUMNS … LIKE …`)와 정의(`SHOW CREATE TABLE`).
3. **그 버전이 `success=1`이면 객체를 건드리지 않는다** — `repair`는 성공한 마이그레이션을 되감지도 재실행하지도 않으므로, 지우면 되살릴 방법이 없다.
4. 성공 이력이 없거나 `success=0`이면, **정의가 기대 스키마와 같고 데이터가 없을 때만** 잔여 객체를 수동으로 지운다(FK 자식 먼저). 데이터가 있거나 정의가 다르면 지우지 않는다 — 스키마와 이력을 서로 다른 시점으로 복원한 상태일 수 있으므로 백업 후 원인을 따로 확인한다.
5. 같은 마이그레이션 구성으로 `flyway repair`(실패 이력만 정리) → 재기동(`migrate`).
