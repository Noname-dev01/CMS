-- ============================================================
-- V24: 본문 이미지 테이블 3종 (2026-10-07)
--
-- HTML 편집기 본문 이미지 업로드(adversarial-review/plan/PLAN-html-editor.md
-- 쟁점 6·9).
--
-- content_image      : 업로드된 본문 이미지 메타데이터. 파일은 FileStorage
--                      루트(공지 첨부와 같은 영역, 네임스페이스 없음)에 있다.
-- content_image_ref  : 어떤 콘텐츠가 어떤 이미지를 참조하는지(공개 판정의
--                      근거). 콘텐츠 저장 트랜잭션이 (owner_type, owner_id)
--                      단위로 교체한다. image_id FK는 RESTRICT — 참조가 남은
--                      이미지 행을 지우지 못하게 한다(수동 회수 절차는 참조를
--                      먼저 지운다).
-- content_image_usage: 전체 바이트·개수 카운터 행 1개(id=1). 업로드가 이 행을
--                      비관적 락으로 잡아 상한을 정확히 집행한다(쟁점 9). 행
--                      시드는 V25(DML)가 한다.
--
-- DDL 3문 — 문마다 암묵 커밋이라 중단 시 일부 테이블만 남을 수 있다. 실패
-- 복구는 docs/migration-guide.md "V23·V24 실패 복구" 참조.
-- ============================================================

CREATE TABLE `content_image` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `storage_key` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `uploader_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_image_storage_key` (`storage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `content_image_ref` (
  `owner_type` varchar(30) NOT NULL,
  `owner_id` bigint(20) NOT NULL,
  `image_id` bigint(20) NOT NULL,
  PRIMARY KEY (`owner_type`, `owner_id`, `image_id`),
  KEY `idx_content_image_ref_image_id` (`image_id`),
  CONSTRAINT `fk_content_image_ref_image_id` FOREIGN KEY (`image_id`) REFERENCES `content_image` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `content_image_usage` (
  `id` bigint(20) NOT NULL,
  `total_bytes` bigint(20) NOT NULL,
  `total_count` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
