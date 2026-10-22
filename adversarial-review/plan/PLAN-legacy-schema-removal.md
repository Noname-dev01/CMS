# PLAN — 레거시 스키마 제거 (V3)

> 작성 2026-10-22 · v1 · 출처: 로드맵 51차 갱신 기록의 후속 후보 ②(`PLAN-migration-squash.md` 설계 1 "범위 밖")

## Context

2026-10-21 기준 스키마 합치기(#167·#168)는 "순수 합치기"를 지키려고 앱이 더 이상 쓰지 않는 스키마를 그대로 V1에 옮겼다. 이번 작업은 그 흔적을 새 마이그레이션 V3로 지운다. 운영 DB는 아직 없고, dev DB는 이미 V2까지 적용돼 있다.

### 정찰에서 확인한 사실 (2026-10-22, master `9218e6f`)

| 대상 | 앱 코드 | 시험·스크립트·문서 |
|---|---|---|
| 테이블 `notice`·`notice_attachment`(FK `fk_notice_attachment_notice_id`) | 엔티티·쿼리 없음(`src/main/generated/QNotice.java`는 git 비추적 IDE 산출물) | 시험·스크립트 참조 없음. 문서: 루트 `CLAUDE.md` 핵심 도메인 모델, `com.cms.admin.board` `CLAUDE.md`, `docs/deployment.md` "공지 게시판"·수동 회수 ⑤ 주석, `LocalDiskFileStorageTest` 주석 1줄 |
| `banner.storage_key`·`content_type`·`file_size` + `uk_banner_storage_key` | 엔티티 매핑 없음, 앱은 읽지도 쓰지도 않음(연결은 `content_image_ref`) | `BaselineSchemaTest.bannerAndPopupConstraints`, `MediaLibraryApiIntegrationTest:290`("사본 컬럼은 NULL") |
| `popup.storage_key`·`content_type`·`file_size` + `uk_popup_storage_key` | 위와 같음 | **시험 픽스처가 이 컬럼에 기댄다**: `PopupConcurrencyIntegrationTest`(픽스처 INSERT·정리 루프), `PopupAdminFormBrowserTest.insertImagePopup`, `PopupPermissionMatrixIntegrationTest.cleanPopups`(항상 NULL인 컬럼을 돌아 파일을 못 지움), `PopupApiIntegrationTest:264`("HTML형은 NULL"), `BaselineSchemaTest` |
| `content_image.scope_type DEFAULT 'NOTICE'` | 엔티티가 항상 값을 넣음 | 시험의 모든 `INSERT INTO content_image`가 `scope_type`을 명시(grep 0건 예외). 문서: 루트 `CLAUDE.md` 지도표 contentimage 행, `com.cms.admin.contentimage` `CLAUDE.md`, `docs/deployment.md` 업로드 출처 |
| `member.profile_image_kind`의 `LEGACY_INLINE` + `ProfileImageMigrationRunner` + `idx_member_profile_image_kind` | 러너(`CommandLineRunner`)·`ProfileImageKind.LEGACY_INLINE`·`Member.migrateProfileImageToStorage`·`MemberRepository.findIdsByProfileImageKind`/`resetIfOversizedLegacyImage`가 오직 이관용. 인덱스는 러너의 `findIdsByProfileImageKind`만 쓴다 | `ProfileImageMigrationRunnerTest`·`…IntegrationTest`, `ProfileImageUrlsTest`의 LEGACY 케이스, 주석(`ProfileImageUrls`·`ProfileImageValidator`(+Test)·`StorageFileNotFoundException`·`Member`). 문서: 루트 `CLAUDE.md` 주의사항, `com.cms.admin.member` `CLAUDE.md` |

`LEGACY_INLINE` 행을 만드는 경로는 현재 앱에 없다 — 옛 백필 마이그레이션이 만들던 값이고, 합치기(2026-10-21) 뒤 모든 DB를 새로 만들었으며 V2 시드도 만들지 않는다. 볼륨 이름 `cms_notice_attachments_*`는 테이블과 무관하다(바꾸면 기존 볼륨이 끊긴다).

## 핵심 확정 사항 (사용자 결정 2026-10-22)

1. **지금 V3로 지운다**(마지막 합치기로 미루지 않음). 마지막 합치기 때 V3는 V1에 흡수되고 이번 업그레이드 경로 시험도 그때 정리된다.
2. 확정 대상(동결 테이블·배너/팝업 옛 이미지 컬럼)에 더해 **`scope_type` 기본값 제거**와 **`LEGACY_INLINE`·이관 러너 제거**를 범위에 넣는다.

## 설계

### 설계 1 — V3 한 파일, 문장 순서는 "실패할 수 있는 문장 먼저"

파일: `src/main/resources/db/migration/V3__drop_legacy_schema.sql`. 순수 DDL(DML 없음 — 가이드 "DML에 DDL을 섞지 않는다").

```sql
-- ① 실패할 수 있는 유일한 문장을 맨 앞에: LEGACY_INLINE 행이 있으면 엄격 모드에서 오류로 멈춘다(아래 쟁점 2)
SET STATEMENT sql_mode = 'STRICT_ALL_TABLES' FOR
ALTER TABLE `member`
  DROP INDEX IF EXISTS `idx_member_profile_image_kind`,
  MODIFY `profile_image_kind` enum('NONE','PRESET','UPLOADED') NOT NULL DEFAULT 'NONE';
-- ② 기본값 제거(기존 행 무영향)
ALTER TABLE `content_image` ALTER COLUMN `scope_type` DROP DEFAULT;
-- ③ 배너·팝업 옛 이미지 컬럼
ALTER TABLE `banner` DROP INDEX IF EXISTS `uk_banner_storage_key`,
  DROP COLUMN IF EXISTS `storage_key`, DROP COLUMN IF EXISTS `content_type`, DROP COLUMN IF EXISTS `file_size`;
ALTER TABLE `popup` (같은 형태);
-- ④ 동결 테이블(자식 먼저)
DROP TABLE IF EXISTS `notice_attachment`;
DROP TABLE IF EXISTS `notice`;
```

- **쟁점 1 — 한 파일 vs 대상별 여러 파일**: (a) 한 파일 V3, (b) V3~V6 넷. → **(a)**. 마지막 합치기로 어차피 사라질 파일이고, 한 PR·한 업그레이드 시험으로 다루는 편이 단순하다. MariaDB DDL은 문장마다 암묵 커밋이라 중간 실패 시 부분 적용이 남지만, 실패할 수 있는 문장을 ①로 두면 ①에서 멈출 때 **아무것도 바뀌지 않는다**. ②~④는 `IF EXISTS`라 실패 후 재실행이 막히지 않는다(가이드 "DDL 실패 복구"의 `repair` → 재기동).
- **쟁점 2 — `LEGACY_INLINE` 행이 남아 있을 때**: (a) 엄격 모드 `ALTER`가 오류로 멈춤(fail-closed), (b) 앞선 `UPDATE … SET kind='NONE'`으로 변환(조용한 데이터 손실 + DML/DDL 혼합), (c) 아무 처리 없음. → **(a)**. 비엄격 모드에서 `ALTER … MODIFY enum`은 범위 밖 값을 빈 문자열 `''`로 바꾸고 경고만 남겨, 그 회원 행을 읽는 순간 Hibernate enum 매핑이 실패한다(로그인 불가). 서버 기본 `sql_mode`(MariaDB 10.11은 `STRICT_TRANS_TABLES` 포함)에 기대지 않으려고 **`SET STATEMENT sql_mode='STRICT_ALL_TABLES' FOR`로 이 문장에만 고정**한다(이미 `ContentCounterWriter`가 쓰는 MariaDB 문법). 그런 행이 있으면 운영자가 처리(옛 러너가 하던 판단)한 뒤 `repair` → 재기동한다 — 이 절차를 `docs/migration-guide.md` V3 행에 적는다. 현재 그런 행을 만들 경로가 없으므로 실제로 걸릴 DB는 없다고 본다.
- **쟁점 3 — 동결 테이블·옛 컬럼에 데이터가 있을 때 막을지**: (a) 가드 없이 DROP, (b) 비어 있지 않으면 실패하는 가드(익명 `BEGIN NOT ATOMIC … SIGNAL` 블록 등). → **(a)**. 2026-10-09 이후 쓰는 코드가 없고 2026-10-21에 모든 DB를 새로 만들어 비어 있다는 것이 사실로 확인되며, 옛 컬럼은 앱이 쓰지 않은 미사용 사본이라 보존할 가치가 없다. 가드는 Flyway의 MariaDB 복합문 파싱이라는 새 위험을 들여오는 데 비해 지킬 데이터가 없다. 운영 DB가 생긴 뒤였다면 판단이 달랐을 것이다(가이드 "되돌릴 수 없는 마이그레이션은 백업 절차" — 실배포 이후 규칙).
- **쟁점 4 — `idx_member_profile_image_kind`도 지울지**: 쓰는 쿼리가 러너의 `findIdsByProfileImageKind` 하나뿐이고 러너와 함께 사라진다 → **지운다**. 회원 수가 적어 다른 쿼리의 계획에 영향이 없다.
- **쟁점 5 — `profile_image_url LONGTEXT`를 `varchar`로 줄일지**: Base64를 담던 흔적이지만 컬럼 타입 변경은 엔티티 `@Lob`/`columnDefinition` 변경과 기존 값 길이 검증이 따르는 별도 위험이다 → **범위 밖(후속 후보)**. 사용자가 정한 범위는 enum 값·러너 제거다.

### 설계 2 — 앱 코드 변경 (회원 도메인만)

- 삭제: `ProfileImageMigrationRunner`, `ProfileImageKind.LEGACY_INLINE`, `Member.migrateProfileImageToStorage`, `MemberRepository.findIdsByProfileImageKind`·`resetIfOversizedLegacyImage`.
- 주석 정리: `ProfileImageKind`(UPLOADED 설명의 `migrateProfileImageToStorage` 언급), `ProfileImageUrls`("PRESET/LEGACY_INLINE" → "PRESET"), `ProfileImageValidator`("업로드·마이그레이션 양쪽" → 업로드), `StorageFileNotFoundException`(러너 예시), `Member` 필드 주석("미이관 레거시").
- 배너·팝업·게시판 앱 코드는 바꾸지 않는다(주석만 — `Banner.java`·`Popup.java`의 "옛 사본 컬럼" 문장 삭제).
- **롤백 호환**: V3가 적용된 DB에서 이전 앱(V2까지 아는 앱)을 다시 띄우면 — Flyway 기본 `ignoreMigrationPatterns=*:future`라 모르는 V3는 무시되고, 이전 엔티티는 지운 컬럼을 매핑하지 않으며(`ddl-auto: validate` 통과), 이전 러너는 `LEGACY_INLINE` 조회 결과 0건으로 끝나고, 이전 `ContentImage`는 항상 `scope_type`을 넣는다. 따라서 되돌림에 스키마 복구가 필요 없다(업그레이드 시험에서 이전 앱 기동까지는 하지 않는다 — 근거는 각 사실의 코드 확인).

### 설계 3 — 시험

- **업그레이드 경로 시험(신규)** `src/test/java/com/cms/LegacySchemaRemovalUpgradeTest.java`(`MariaDbContainerSupport` 상속, `BaselineSchemaTest`와 같은 "별도 스키마 + Flyway" 방식):
  1. V2까지(`target("2")`) 적용 → 옛 컬럼을 채운 배너·팝업, 동결 테이블 행, `NONE`/`PRESET`/`UPLOADED` 회원, `content_image` 행을 넣음 → V3 적용 → 동결 테이블 없음, 배너·팝업 옛 컬럼·유니크 없음, **나머지 컬럼 값은 그대로**, 회원 kind 그대로, enum 정의 3값, 인덱스 없음, `flyway_schema_history`의 V3 `success=1`.
  2. V2 상태에 `LEGACY_INLINE` 회원 1행 → V3 적용이 실패하고 그 행과 **다른 대상(동결 테이블·배너 옛 컬럼·`scope_type` 기본값)이 모두 그대로**(①에서 멈춤 증명) → 행을 정리(운영자 조치 흉내) → `repair` → `migrate` 성공.
  3. 서버 `sql_mode`를 비엄격으로 둔 연결에서도 2가 실패한다(`SET STATEMENT` 고정 증명 — Flyway 데이터소스의 `sessionVariables=sql_mode=''`로 비엄격 세션을 만든다).
- **`BaselineSchemaTest`** (새 설치 = V1~V3): 클래스 주석의 "V1·V2"를 "V1~V3"으로, `bannerAndPopupConstraints`에서 옛 컬럼 단언 제거(`storage_key` 유일·NULL·이미지형 INSERT), 새 단언 `legacySchemaRemoved` — 동결 테이블 없음, 배너·팝업 옛 컬럼 없음, `member.profile_image_kind` enum 3값·인덱스 없음, `scope_type`을 생략한 `content_image` INSERT 거부(엄격 모드 세션에서). 테이블 수는 단언하지 않는다(현재도 없음).
- **팝업 시험 픽스처를 실제 앱 상태로**: `PopupConcurrencyIntegrationTest.fixturePopup`·`PopupAdminFormBrowserTest.insertImagePopup`를 `PopupPermissionMatrixIntegrationTest.fixturePopup`과 같은 형태(옛 컬럼 없이 INSERT + `MediaTestSupport.attachImage`)로 바꾸고, `PopupConcurrencyIntegrationTest`·`PopupPermissionMatrixIntegrationTest`의 정리 루틴을 `DELETE FROM popup` + `MediaTestSupport.cleanLibrary`로 바꾼다(다른 팝업 시험과 같은 형태). `PopupAdminFormBrowserTest`의 이미지형 미리보기는 이제 실제로 이미지가 연결된 상태에서 시험된다.
- **"옛 컬럼은 NULL" 단언 2건 삭제**(`MediaLibraryApiIntegrationTest:290`, `PopupApiIntegrationTest:264`) — 컬럼 부재는 `BaselineSchemaTest`가 고정한다. HTML형에 이미지 참조가 없다는 계약은 `PopupApiIntegrationTest`가 `content_image_ref`로 이미 단언하는지 확인하고, 없으면 그 단언으로 바꾼다.
- **러너 시험 삭제**: `ProfileImageMigrationRunnerTest`·`ProfileImageMigrationRunnerIntegrationTest`. `ProfileImageUrlsTest`의 LEGACY 케이스 삭제. `ProfileImageValidatorTest` 주석 정리.
- 실행 범위(루트 `CLAUDE.md` 작업 방식 4 — 마이그레이션 변경은 공용 코드): 개발 중 `com.cms.*Schema*`·`com.cms.admin.popup.*`·`com.cms.admin.media.*`·`com.cms.admin.member.*`·`*ConventionTest`, 마무리에 전체 `./gradlew test`.

### 설계 4 — 문서

- `docs/migration-guide.md`: 구성 표에 V3 행(내용 + "`LEGACY_INLINE` 행이 있으면 ①에서 멈춤 → 정리 후 `repair`·재기동"), "테이블 28개"는 V1 설명이라 그대로 두고 V3 행에 "테이블 2개 삭제".
- `docs/deployment.md`: "공지 게시판" 절의 동결 테이블 문단 삭제(제거 사실 한 줄로), 수동 회수 ⑤의 `notice_attachment` 주석 2줄 삭제, 업로드 출처 문단의 "컬럼 기본값 `NOTICE`" 문장 삭제.
- 루트 `CLAUDE.md`: 핵심 도메인 모델의 "`notice`·`notice_attachment` 테이블은 동결 스냅샷" 삭제, 지도표 contentimage 행의 "`NOTICE`는 컬럼 기본값에만 남은 흔적" 삭제, 주의사항의 프로필 `LEGACY_INLINE` 항목 삭제.
- 패키지 `CLAUDE.md`: `board`(동결 테이블 문장), `media`(옛 컬럼·"컬럼 삭제는 후속 PR"), `banner`·`popup`(옛 컬럼 서술·스키마 줄), `contentimage`(기본값 흔적), `member`(`LEGACY_INLINE`·1회성 이관 절).
- V1 파일의 해당 주석은 머지된 파일이라 고치지 않는다 — V3 머리 주석에 무엇을 왜 지우는지 쓴다.
- `LocalDiskFileStorageTest:216` 주석의 "오염된 notice_attachment.storage_key 흉내" → "오염된 첨부 storage_key 흉내".

### 설계 제약

- 머지된 V1·V2 수정 금지. 인가 정책·`SecurityConfig`·API·화면 변경 없음.
- dev DB(`cms-db-dev`)는 재생성 없이 새 앱 기동 시 V3가 적용된다.

## 작업 단계

1. 브랜치 `chore/legacy-schema-removal`.
2. V3 작성 → `BaselineSchemaTest` 갱신 + 업그레이드 시험 작성 → 실행.
3. 회원 도메인 코드 삭제·주석 정리 → `compileJava`·`compileTestJava`.
4. 팝업·미디어 시험 픽스처·단언 정리 → 영향 시험 실행.
5. 문서 갱신.
6. 실기 검증: dev 앱을 새 코드로 기동해 V3 적용 확인(`flyway_schema_history`, `SHOW CREATE TABLE`), 배너·팝업 등록→공개 메인 노출→삭제 왕복, 프로필 이미지 업로드/프리셋/초기화 왕복, 공지 게시판 목록, 미디어 라이브러리 사용처 표시(Playwright 스크린샷).
7. 전체 `./gradlew test`(code-review-loop 마무리).

## 리스크

| 리스크 | 대응 |
|---|---|
| 되돌릴 수 없는 삭제(테이블·컬럼) | 운영 DB 없음, 대상은 비었거나 미사용임을 확인. 롤백 앱은 스키마 복구 없이 기동(설계 2) |
| `LEGACY_INLINE` 행이 있는 DB | ①에서 fail-closed, 부분 적용 없음, 복구 절차 문서화(쟁점 2), 시험 2·3 |
| 시험 픽스처 누락으로 CI에서만 깨짐 | 옛 컬럼 참조 grep 0건을 완료 조건으로(`storage_key` 등을 `banner`/`popup` 문맥에서) |
| 마지막 합치기 때 V3 흡수 누락 | 합치기 절차가 V3~Vn 전부를 대상으로 함(가이드) — 이번 업그레이드 시험도 그때 정리 |

## 범위 밖 · 후속 후보

- `member.profile_image_url LONGTEXT` 축소(쟁점 5).
- 볼륨 이름 `cms_notice_attachments_*` 변경.

## 개정 이력

- v1 (2026-10-22): 최초 작성.
- v1 리뷰 1라운드(codex gpt-6.1-sol): **ship** — 실질 지적 없음. 확인된 전제: `MediaImageRegistrar`가 `scopeType`을 명시, 이전 러너는 `LEGACY_INLINE` 0건이면 쓰기 없음, `notice_attachment` → `notice` 삭제 순서가 FK 방향과 일치. 리뷰어 단서: 엄격 모드 실패 원자성·이전 앱 재기동은 정적 검토로 확인하지 않았으므로 업그레이드 시험(설계 3의 2·3)과 실기 검증으로 확인할 것.

## 구현·검증 결과 (2026-10-22)

### 구현 파일
- 마이그레이션: `V3__drop_legacy_schema.sql`(① member enum 축소+인덱스 삭제를 `SET STATEMENT sql_mode='STRICT_ALL_TABLES'`로 맨 앞 → ② `scope_type` 기본값 제거 → ③ 배너·팝업 옛 컬럼·유니크 → ④ `notice_attachment`·`notice`).
- 삭제: `ProfileImageMigrationRunner` + 시험 2개, `ProfileImageKind.LEGACY_INLINE`, `Member.migrateProfileImageToStorage`, `MemberRepository`의 이관 전용 쿼리 2개.
- 시험: `LegacySchemaRemovalUpgradeTest`(신규 3건), `BaselineSchemaTest`(옛 컬럼 단언 제거 + `legacySchemaRemoved` 추가), 팝업 시험 픽스처 3곳을 `MediaTestSupport.attachImage`·`cleanLibrary` 방식으로, "옛 컬럼은 NULL" 단언 2건 정리.
- 문서: 루트 `CLAUDE.md`, `docs/migration-guide.md`(V3 행·실패 복구), `docs/deployment.md`, 패키지 `CLAUDE.md` 6곳(board·media·banner·popup·member·contentimage).

### 검증 결과
- 영향 범위 시험(`BaselineSchemaTest`·`LegacySchemaRemovalUpgradeTest`·`admin.member/popup/media/banner`·`publicweb.popup/home`·`common.storage`·`*ConventionTest`): BUILD SUCCESSFUL.
- 업그레이드 시험이 확인한 것: 지울 것만 지워지고 남는 컬럼 값·회원 프로필 종류가 그대로, `LEGACY_INLINE` 행이 있으면 실패하고 **다른 대상이 하나도 바뀌지 않으며**, 서버 `sql_mode=''`에서도 실패("Data truncated"), 행 정리 후 `repair`→`migrate` 성공.
- 실기(dev, Playwright·브라우저 세션 API): V3 적용 전 dev DB 상태 기록(V2, 데이터 비어 있음) → 앱 재빌드 기동 시 V3 자동 적용(`flyway_schema_history` 3 success, 스키마 확인) → 로그인, 이미지형 팝업 등록(201)·관리자/공개 이미지 200, 배너 등록(201), 미디어 사용처 `BANNER`, 게시판 목록 200, 프로필 업로드→다운로드→프리셋→초기화 왕복 → 삭제 후 공개 이미지 404·미디어 0건으로 원복. 스크린샷: `legacy-schema-removal-screenshots/`.
- 앱 로그 ERROR/WARN 없음. 브라우저 콘솔 오류는 검증 중 제가 잘못 보낸 요청(파트 이름·preset 형식)의 400과 업로드 전 다운로드의 404뿐이다.

### 이슈
- 없음(구현 중 계획 변경 없음). 검증 중 제 요청 형식 오류로 400이 두 번 났으나 앱 회귀가 아님을 컨트롤러·DTO 확인으로 구분했다.

### 후속
- `member.profile_image_url LONGTEXT` 축소, 볼륨 이름 `cms_notice_attachments_*` 변경(범위 밖).
- 첫 실배포 직전 마지막 합치기 때 V3를 V1에 흡수하고 `LegacySchemaRemovalUpgradeTest`를 지운다.
- 전체 `./gradlew test`는 `code-review-loop` 마무리에서 1회 실행한다.
