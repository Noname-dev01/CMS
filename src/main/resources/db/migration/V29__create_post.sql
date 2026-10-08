-- ============================================================
-- V29: 게시글 + 게시글 첨부 테이블 (2026-10-08)
--
-- 범용 게시판 PR B(adversarial-review/plan/PLAN-board.md 쟁점 7·D-3).
--
-- post            : 게시판(board)에 속한 게시글. notice와 같은 모양 — use_yn(노출)과 deleted(소프트 삭제)는 별도 컬럼이고,
--                   content는 HTML(MEDIUMTEXT — V23 이후 공지 본문과 같다), author_id는 작성자 userId 스냅샷이다.
--                   게시판은 여러 개가 한 테이블을 나눠 쓰므로 목록·COUNT가 board_id로 시작하는 복합 인덱스를 탄다
--                   (board_id, deleted, use_yn, create_date) — board_id FK도 이 인덱스의 선두 컬럼으로 충족된다.
--                   board는 소프트 삭제라 FK(RESTRICT)가 게시판 삭제를 막지 않고, 게시판 삭제는 살아 있는 게시글이 없을 때만 허용된다.
-- post_attachment : 게시글 첨부. notice_attachment와 같은 모양(storage_key UNIQUE, (post_id, id) 인덱스). 파일은 스토리지 루트에 저장된다
--                   (수동 회수 절차는 docs/deployment.md "편집기 본문 이미지" — 보존 목록에 이 테이블의 storage_key를 포함한다).
--
-- DDL 2문 — 문마다 암묵 커밋. 실패 복구는 docs/migration-guide.md "V29~V30".
-- ============================================================

CREATE TABLE `post` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `board_id` bigint(20) NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` MEDIUMTEXT NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `author_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_post_board_deleted_use_create` (`board_id`, `deleted`, `use_yn`, `create_date`),
  CONSTRAINT `fk_post_board` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `post_attachment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `post_id` bigint(20) NOT NULL,
  `original_filename` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_attachment_storage_key` (`storage_key`),
  KEY `idx_post_attachment_post_id_id` (`post_id`, `id`),
  CONSTRAINT `fk_post_attachment_post_id` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
