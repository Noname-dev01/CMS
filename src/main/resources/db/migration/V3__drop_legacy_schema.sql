-- ------------------------------------------------------------
-- 옛 스키마 정리 (PLAN-legacy-schema-removal.md). 앱이 읽지도 쓰지도 않는 흔적을 지운다. 순수 DDL이다.
--
-- 순서가 중요하다: MariaDB DDL은 문장마다 암묵 커밋이라 중간에 끊기면 일부만 적용된다. 실패할 수 있는 유일한 문장(① member enum 축소)을
-- 맨 앞에 두어, 거기서 멈추면 아무것도 바뀌지 않게 한다. 나머지는 IF EXISTS라 실패 뒤 `flyway repair` → 재기동으로 다시 실행해도 막히지 않는다.
-- ------------------------------------------------------------

-- ① 프로필 이미지: 옛 Base64 이관(ProfileImageMigrationRunner)이 사라져 LEGACY_INLINE 값과 그 조회 인덱스를 지운다.
--    LEGACY_INLINE 행이 남아 있으면 엄격 모드에서 이 문장이 오류로 멈춘다(비엄격이면 그 값이 ''로 바뀌어 회원 행을 읽을 수 없게 된다).
--    서버 기본 sql_mode에 기대지 않으려고 이 문장에만 STRICT_ALL_TABLES를 고정한다.
SET STATEMENT sql_mode = 'STRICT_ALL_TABLES' FOR
ALTER TABLE `member`
  DROP INDEX IF EXISTS `idx_member_profile_image_kind`,
  MODIFY `profile_image_kind` enum('NONE','PRESET','UPLOADED') NOT NULL DEFAULT 'NONE';

-- ② 이미지 출처 기본값 'NOTICE'는 옛 공지 출처의 흔적이다. 앱은 항상 값을 넣으므로 기본값을 없애 누락을 오류로 만든다(기존 행은 그대로).
ALTER TABLE `content_image` ALTER COLUMN `scope_type` DROP DEFAULT;

-- ③ 배너·팝업의 옛 직접 저장 컬럼. 이미지 연결은 content_image_ref 한 곳이다.
ALTER TABLE `banner`
  DROP INDEX IF EXISTS `uk_banner_storage_key`,
  DROP COLUMN IF EXISTS `storage_key`,
  DROP COLUMN IF EXISTS `content_type`,
  DROP COLUMN IF EXISTS `file_size`;

ALTER TABLE `popup`
  DROP INDEX IF EXISTS `uk_popup_storage_key`,
  DROP COLUMN IF EXISTS `storage_key`,
  DROP COLUMN IF EXISTS `content_type`,
  DROP COLUMN IF EXISTS `file_size`;

-- ④ 공지 흡수(2026-10-09) 전 데이터의 동결 스냅샷. 자식(FK) 먼저 지운다.
DROP TABLE IF EXISTS `notice_attachment`;
DROP TABLE IF EXISTS `notice`;
