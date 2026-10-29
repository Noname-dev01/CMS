-- ------------------------------------------------------------
-- 게시글 대표 이미지 + 이미지 썸네일 (PLAN-board-definition.md "PR 5 상세 설계 — 대표 이미지 + 썸네일").
-- post.cover_image_id     : 그 글 본문이 참조하는 이미지 중 하나. 불변식 "대표 ∈ 그 글의 참조"는 서비스가 지키고, FK ON DELETE SET NULL은
--                           롤백한 이전 앱이 불변식을 깬 경우에도 이미지 삭제를 막지 않고 대표만 비우는 최후 방어선이다(RESTRICT면 이전 앱의 삭제가 FK 오류).
-- content_image_thumb     : 이미지 단위 썸네일(이미지 하나에 행 하나, PK = image_id라 동시 생성은 INSERT IGNORE로 한쪽만 남는다).
--                           파일은 FileStorage 예약 네임스페이스 thumb/ 에 있다. 이미지 행이 지워지면 CASCADE로 함께 사라진다(파일은 앱이 커밋 후 지운다).
--                           카운터(content_image_usage)에는 바이트만 포함한다(개수는 업로드 개수 약속이라 제외).
-- cover_image_id는 NULL 허용·DEFAULT NULL이라 기존 행·롤백한 이전 앱이 만든 행 모두 "대표 없음"으로 지금과 같게 동작한다
-- (업그레이드 경로 시험 PostCoverImageUpgradeTest). 모든 절이 IF NOT EXISTS라 부분 적용 뒤 flyway repair 하고 다시 실행해도 된다.
-- ------------------------------------------------------------
ALTER TABLE `post`
  ADD COLUMN IF NOT EXISTS `cover_image_id` bigint(20) NULL DEFAULT NULL,
  ADD CONSTRAINT `fk_post_cover_image` FOREIGN KEY IF NOT EXISTS (`cover_image_id`) REFERENCES `content_image` (`id`) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS `content_image_thumb` (
  `image_id` bigint(20) NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`image_id`),
  UNIQUE KEY `uk_content_image_thumb_storage_key` (`storage_key`),
  CONSTRAINT `fk_content_image_thumb_image` FOREIGN KEY (`image_id`) REFERENCES `content_image` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
