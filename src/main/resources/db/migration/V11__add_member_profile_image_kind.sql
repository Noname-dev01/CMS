-- ============================================================
-- V11: member 테이블에 프로필 이미지 종류(profile_image_kind)·
--       Content-Type(profile_image_content_type) 컬럼 추가 (2026-08-10)
--
-- 로드맵 "실행 로드맵 Top 3 (2026-07-29 선정) — ③ 프로필 이미지
-- Base64-in-DB → FileStorage 이관" (adversarial-review/plan/PLAN-profile-image-storage.md
-- 참조, v6 — 적대적 리뷰 5라운드 종료 후 마지막 지적 반영).
--
-- profile_image_kind는 profile_image_url 값의 "지금 의미"를 명시적으로
-- 담는다 — 문자열 생김새(정규식·접두어)로 추론하지 않는다:
--   NONE          이미지 없음 (profile_image_url = NULL)
--   PRESET        정적 프리셋 경로 4종 중 하나
--   UPLOADED      FileStorage에 실파일로 저장된 storageKey
--                 (com.cms.common.storage.FileStorage의 "profile" 네임스페이스에만
--                  존재 — 공지 첨부파일과 물리적으로 분리된 디렉터리)
--   LEGACY_INLINE 미이관 레거시 data:<mime>;base64,... 값 (pass-through 렌더링)
--
-- profile_image_content_type은 UPLOADED일 때만 채워지며, 다운로드 응답의
-- Content-Type을 서빙 시점에 확장자 추론 없이 그대로 재생하는 데 쓰인다.
--
-- 기존 profile_image_url(LONGTEXT) 컬럼은 타입·이름 변경 없음 — 레거시
-- data URI가 마이그레이션 러너(ProfileImageMigrationRunner)에 의해 이관되기
-- 전까지 남아있을 수 있어 트렁케이션 위험을 피한다.
-- ============================================================

ALTER TABLE `member`
  ADD COLUMN `profile_image_kind` ENUM('NONE','PRESET','UPLOADED','LEGACY_INLINE') NOT NULL DEFAULT 'NONE' AFTER `profile_image_url`,
  ADD COLUMN `profile_image_content_type` VARCHAR(100) NULL AFTER `profile_image_kind`,
  ADD INDEX `idx_member_profile_image_kind` (`profile_image_kind`);

-- 1) 프리셋 4종 백필: 대소문자 무관 매칭(collation이 이미 _ci) + canonical 값으로 재기록
--    (매칭은 대소문자 무관이어도 원본 값을 그대로 남기면 대소문자를 구분하는 정적 리소스
--    경로에서 깨질 수 있어, 매칭된 행은 항상 정확한 canonical 문자열로 다시 쓴다).
UPDATE `member` SET `profile_image_kind` = 'PRESET', `profile_image_url` = '/img/undraw_profile.svg'
  WHERE `profile_image_url` = '/img/undraw_profile.svg' COLLATE utf8mb4_general_ci;
UPDATE `member` SET `profile_image_kind` = 'PRESET', `profile_image_url` = '/img/undraw_profile_1.svg'
  WHERE `profile_image_url` = '/img/undraw_profile_1.svg' COLLATE utf8mb4_general_ci;
UPDATE `member` SET `profile_image_kind` = 'PRESET', `profile_image_url` = '/img/undraw_profile_2.svg'
  WHERE `profile_image_url` = '/img/undraw_profile_2.svg' COLLATE utf8mb4_general_ci;
UPDATE `member` SET `profile_image_kind` = 'PRESET', `profile_image_url` = '/img/undraw_profile_3.svg'
  WHERE `profile_image_url` = '/img/undraw_profile_3.svg' COLLATE utf8mb4_general_ci;

-- 2) 레거시 data URI 백필 — ProfileImageMigrationRunner가 다음 기동에서 이관을 시도한다.
UPDATE `member` SET `profile_image_kind` = 'LEGACY_INLINE'
  WHERE `profile_image_url` LIKE 'data:%';

-- 3) catch-all: 위 두 UPDATE로 분류되지 않았지만 값이 남아있는 행(빈 문자열·손상값·
--    알 수 없는 형식 등) — "NONE인데 URL이 남아있는" 불가능한 상태를 방지하기 위해
--    안전한 쪽(pass-through 렌더링)으로 흡수한다. 러너가 data: 파싱에 실패하면 기존
--    실패 정책(스킵, LEGACY_INLINE 유지)으로 자연히 처리된다.
UPDATE `member` SET `profile_image_kind` = 'LEGACY_INLINE'
  WHERE `profile_image_kind` = 'NONE' AND `profile_image_url` IS NOT NULL;
