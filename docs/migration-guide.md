# Flyway 마이그레이션 가이드

> 작성일: 2026-07-10 (Flyway 도입 PR) · 2026-10-21 기준 스키마로 합침(`adversarial-review/plan/PLAN-migration-squash.md`)

## 기본 원칙

- **스키마 변경은 Flyway 마이그레이션 파일로만 한다.** `ddl-auto`는 `validate`로 고정되어 있어 Hibernate가 스키마를 변경하지 않는다.
- 마이그레이션 파일 위치: `src/main/resources/db/migration/`
- 파일명 규칙: `V{버전}__{설명}.sql` (버전은 마지막 버전 +1, 설명은 snake_case). 코드로만 할 수 있는 데이터 변환은 `src/main/java/db/migration/V{버전}__{설명}.java`(`BaseJavaMigration`, JDBC만) — 선례 V10
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
| V6 | `V6__board_display_settings.sql` | 게시판 표시·식별 설정 + 낡은 화면 검출 버전(2026-10-27, `PLAN-board-definition.md` PR 1) — `board`에 `description varchar(500) NULL`·`list_page_size int NOT NULL DEFAULT 10`·`author_label varchar(50) NULL`·`show_view_count bit NOT NULL DEFAULT 0`·`new_badge_days int NOT NULL DEFAULT 0`·`writable_yn bit NOT NULL DEFAULT 1`·`version int NOT NULL DEFAULT 1`(DDL 한 문장, 백필 없음 — 모든 DEFAULT가 현행 동작). 기존 테이블에 `NOT NULL`을 더하므로 `BoardDisplaySettingsUpgradeTest`(V5까지 적용 → 기존 행 삽입 → V6 → 기본값 확인)가 업그레이드 경로를 고정하고 `BaselineSchemaTest`가 새 설치를 고정한다. 이전 앱은 새 컬럼을 모르고 INSERT해도 DEFAULT가 채워져 그대로 동작한다 |
| V7 | `V7__board_write_policy.sql` | 게시판 작성 정책(2026-10-28, `PLAN-board-definition.md` PR 2) — `board`에 `attach_max_count int NOT NULL DEFAULT 5`·`attach_max_size_mb int NOT NULL DEFAULT 10`·`attach_extensions varchar(200) NULL`(NULL = 전역 화이트리스트 전체)·`body_image_yn bit NOT NULL DEFAULT 1`·`publish_period_yn bit NOT NULL DEFAULT 1`(DDL 한 문장, 백필 없음 — 모든 DEFAULT가 현행 동작). 기존 테이블에 `NOT NULL`을 더하므로 `BoardWritePolicyUpgradeTest`(V6까지 적용 → 기존 행 삽입 → V7 → 기본값 확인)가 업그레이드 경로를 고정하고 `BaselineSchemaTest`가 새 설치를 고정한다. 이전 앱은 새 컬럼을 모르고 INSERT해도 DEFAULT가 채워져 그대로 동작한다 |
| V8 | `V8__board_sort_order_and_pin.sql` | 게시판 표시 순서·상단 고정(2026-10-28, `PLAN-board-definition.md` PR 3) — `board`에 `sort_order int NOT NULL DEFAULT 0`·`pin_max_count int NOT NULL DEFAULT 0`(0 = 고정 끔), `post`에 `pinned bit NOT NULL DEFAULT 0`과 인덱스 `idx_post_board_pinned(board_id, pinned, deleted, create_date)`(DDL 두 문장, 백필 없음 — 모든 DEFAULT가 현행 동작). 기존 테이블에 `NOT NULL`을 더하므로 `BoardSortOrderPinUpgradeTest`(V7까지 적용 → 기존 행 삽입 → V8 → 기본값·인덱스 확인, 재실행 멱등)가 업그레이드 경로를 고정하고 `BaselineSchemaTest`가 새 설치를 고정한다. **모든 절이 `IF NOT EXISTS`라 부분 적용 뒤 `flyway repair`를 하고 그대로 다시 실행해도 된다.** 이전 앱은 새 컬럼을 모르고 INSERT해도 DEFAULT가 채워져 그대로 동작한다 |
| V9 | `V9__board_slug.sql` | 게시판 슬러그(2026-10-28, `PLAN-board-definition.md` PR 4a) — `board.slug varchar(50) NULL` + `UNIQUE uk_board_slug`(DDL만, 백필 없음 — **NULL = 기본 슬러그 `board-{id}`**이고 저장하지 않는다). 이전 앱이 만든 행도 NULL이라 그대로 `board-{id}`로 열린다. `IF NOT EXISTS`라 재실행 무해 |
| V10 | `V10__replace_board_links_with_slugs.java` | **이 프로젝트 첫 Java 마이그레이션**(`src/main/java/db/migration/`, 2026-10-28, `PLAN-board-definition.md` PR 4a D6) — 저장된 숫자 게시판 링크 `/{공개 사이트 키}/boards/{id}…`를 `…/boards/board-{id}…`로 치환한다. 대상: 공개 사이트 메뉴 `menu_url`, `banner.link_url`(+`version`), `popup.link_url`·`content_html`(+`revision` 행당 1), `post.content`, **`site_page_revision.content`(모든 개정 — 개정은 불변 기록이지만 이 마이그레이션이 링크를 고친다, 페이지 `version`·개정 번호는 그대로)**, `faq.answer_html`. 아래 설명 참조 |
| V11 | `V11__replace_notice_links_with_board_links.java` | 공개 공지 흡수(2026-10-28, `PLAN-board-definition.md` PR 4b D5) — 저장된 공지 상세 링크 `/{공개 사이트 키}/notices/{postId}…`를 `/{키}/boards/{공지 게시판 슬러그}/posts/{postId}…`로 치환한다. **그 사이트에서 해석되는 공지 게시판(전용 `NOTICE:{siteId}` → 없으면 공통 `NOTICE`, 미삭제) 소속 글만**(삭제·미노출 글 포함 — 지금도 그 주소가 가리키는 글), 대상 테이블·버전 증가·상한은 V10과 같다. 아래 설명 참조 |
| V12 | `V12__post_cover_image_and_thumbnail.sql` | 게시글 대표 이미지 + 이미지 썸네일(2026-10-29, `PLAN-board-definition.md` PR 5) — `post.cover_image_id bigint NULL`(FK `ON DELETE SET NULL` — 롤백한 이전 앱이 "대표 ∈ 본문 참조" 불변식을 깨도 이미지 삭제를 막지 않는다)과 새 테이블 `content_image_thumb`(PK `image_id`, FK `ON DELETE CASCADE`, 파일은 스토리지 예약 네임스페이스 `thumb/`). 모든 절이 `IF NOT EXISTS`(MariaDB는 FK에서 `FOREIGN KEY IF NOT EXISTS (…)` 순서). 기존 행은 대표 없음. 롤백 호환이며 재배포 직후 불변식 복구 SQL·카운터 정합 절차는 `docs/deployment.md` "게시글 대표 이미지·썸네일". 시험 `PostCoverImageUpgradeTest` |
| V13 | `V13__board_type.sql` | 게시판 유형(2026-10-29, `PLAN-board-definition.md` PR 6) — `board.board_type varchar(20) NOT NULL DEFAULT 'LIST'`(DDL만, CHECK 제약 없음 — 값 검증은 서비스, 알 수 없는 저장값은 읽을 때 LIST로 보고 유형을 언급하지 않은 수정은 원본을 보존한다). `ADD COLUMN IF NOT EXISTS`라 재실행 멱등. 기존 행·롤백한 이전 앱이 만든 행은 `LIST`(현행 화면)이고 롤백 호환이다. 시험 `BoardTypeUpgradeTest` |

V10은 DML만이라(DDL은 V9로 분리 — DDL을 섞으면 실패 시 롤백되지 않는다) **실패하면 업무 데이터는 전부 그대로**다. 실패 조건: 치환 결과가 컬럼 길이(메뉴 255자·배너/팝업 링크 500자)나 본문 상한(게시글 200,000·정적 페이지 500,000·팝업 50,000·FAQ 30,000바이트 — 이 마이그레이션 시점 값으로 고정)을 넘는 행, 치환 뒤에도 "지금 열리는" 숫자 게시판 링크(퍼센트 인코딩 `%31%32`·`%62oards`, 점 세그먼트 `/./`·`/x/../`)가 남은 URL 컬럼 행. 메시지가 `테이블#id`(정적 페이지 개정은 `site_page_revision#{page_id}/{revision_no}`)를 최대 20개 알려 준다. 그러나 MariaDB에서 **Flyway는 `success=0` 이력을 남겨 다음 기동을 막으므로** 그 행을 이전 버전 화면(또는 SQL)으로 고친 뒤 `flyway repair` → 재기동한다(위 "DDL 실패 복구" 5번과 같다). 치환 규칙은 런타임과 같은 함수 `PathIds`(id 해석 — `+12`·전각 숫자도 정규형으로)·`BoardLinkPaths`(숫자 게시판 경로 판정)를 부르므로 **두 함수를 바꾸면 아직 V10을 적용하지 않은 DB의 결과가 달라진다** — 바꿀 때 `BoardSlugLinkUpgradeTest`를 함께 본다. Java 마이그레이션은 Flyway 체크섬이 없어 수정 감지가 안 되지만 머지 후 수정 금지 규칙은 같다. 엔티티·스프링 빈은 쓰지 않는다(JDBC만). **첫 실배포 직전 마지막 합치기 때 V9는 V1에 흡수하고 V10은 빈 DB에 할 일이 없어 삭제한다**(`BoardSlugLinkUpgradeTest`도 함께). 업그레이드 경로는 `BoardSlugLinkUpgradeTest`(치환 표·버전 증가·fail-fast → 롤백 → 실패 이력 → 수정 → repair → 재실행)가 고정한다.

V11은 V10과 같은 구조·같은 실패 복구 절차다(DML만, 실패하면 업무 데이터 그대로 → 원인 행 수정 → `flyway repair` → 재기동). 다른 점: ① 치환 여부를 DB로 판정한다 — 링크의 사이트 키로 공지 게시판을 해석하고 글의 `board_id`가 그 게시판일 때만 바꾼다(일반 게시판 글·전용 공지가 대체한 공통 공지 글·다른 사이트 전용 공지 글·없는 글은 지금도 404라 그대로 둔다 — 배포 후 점검 SQL로 찾는다, `docs/deployment.md`). ② **경로가 정규형일 때만**(`%`·`.`·`..` 세그먼트 없음) 치환한다 — 두 세그먼트가 네 세그먼트로 늘어 접미부 `..`의 목적지가 바뀌기 때문이다(`/main/notices/5/../../notices/6`). 정규형이 아닌 값이 지금 열리는 공지 링크면 사후 검사가 실패시킨다. ③ 늘어나는 길이는 링크 하나당 +13자(사용자 슬러그면 슬러그 길이만큼 더). 판정 함수는 `PathIds`·`BoardLinkPaths.numericNoticeTarget`(V10이 쓰는 `numericBoardSiteSegment`와 별개 — V10의 의미를 바꾸지 않으려고 나눴다)이고 바꿀 때 `NoticeLinkUpgradeTest`를 함께 본다. 첫 실배포 직전 마지막 합치기 때 V10과 함께 **삭제**한다(`NoticeLinkUpgradeTest`도 함께).

V4는 DDL 한 문장이고 `STRICT_ALL_TABLES`를 그 문장에 고정했다 — **255자를 넘는 비공백 값이 있으면 오류로 멈추고 아무것도 바뀌지 않는다**(비엄격 모드의 `MODIFY`는 값을 조용히 자른다). 걸리면 그 행을 `Member.resetProfileImage()`처럼 **세 컬럼을 함께** 정리한다 — `UPDATE member SET profile_image_kind = 'NONE', profile_image_url = NULL, profile_image_content_type = NULL WHERE CHAR_LENGTH(profile_image_url) > 255;` — 그 뒤 `flyway repair` → 재기동한다(아래 "DDL 실패 복구"). **한계**: 255자 뒤가 공백뿐인 값은 엄격 모드에서도 오류 없이 255자로 잘린다(MariaDB가 NOTE로만 처리 — 시험으로 확인). 앱 경로로는 공백이 든 값을 만들 수 없어 가드를 두지 않았다. **이전 앱 롤백**: V4가 적용된 DB에서 이전 앱(`@Lob` 매핑)을 띄우면 `ddl-auto: validate`가 `found [varchar], but expecting [longtext (CLOB)]`로 **기동에 실패한다**(실측) — 되돌리려면 먼저 `ALTER TABLE member MODIFY profile_image_url LONGTEXT DEFAULT NULL;`(무손실 확대)을 실행한다. 업그레이드 경로는 `ProfileImageUrlShrinkUpgradeTest`가 고정하며 첫 실배포 직전 마지막 합치기 때 V4도 V1에 흡수되고 이 시험도 함께 지운다.

V3는 순수 DDL이고 **실패할 수 있는 문장(`member` enum 축소)이 맨 앞**이다 — `profile_image_kind = 'LEGACY_INLINE'` 행이 있으면(옛 Base64 프로필 이관 러너가 이관하지 못한 행) 엄격 모드(`SET STATEMENT sql_mode='STRICT_ALL_TABLES'`)에서 그 문장이 오류로 멈추고 **아무것도 바뀌지 않는다**. 그 행을 `NONE`(이미지 없음)으로 정리한 뒤 `flyway repair` → 재기동한다(아래 "DDL 실패 복구"). 나머지 문장은 `IF EXISTS`라 다시 실행해도 막히지 않는다. 업그레이드 경로는 `LegacySchemaRemovalUpgradeTest`가 고정한다 — 첫 실배포 직전 마지막 합치기 때 V3는 V1에 흡수되고 이 시험도 함께 지운다.

V1~V2는 옛 V1~V58(2026-07-10~2026-10-16)을 합친 것이다. 옛 파일과 각 변경의 이유·배포 절차는 git 이력(합치기 직전 커밋 `96baec2`)에 있다. 새로 설치한 결과는 `BaselineSchemaTest`가 고정한다(V3~V13 포함).

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
