-- ============================================================
-- V26: 게시판 + 게시판별 권한 테이블 (2026-10-08)
--
-- 범용 게시판 PR A(adversarial-review/plan/PLAN-board.md 쟁점 1·6).
--
-- board                   : 게시판 정의. public_yn(공개 게시판 여부)·attachment_yn(첨부 허용)은
--                           ADMIN이 정한다. deleted는 소프트 삭제(삭제 API는 PR B — 게시글 검사와 함께).
-- member_board_permission : MANAGER 회원의 게시판별 허용 동작. 기존 member_permission(기능 단위)과
--                           분리해 공지 권한 경로를 건드리지 않는다. 행이 있으면 허용(거부 행 없음).
--                           action은 VARCHAR(DB enum 아님 — 기존 테이블과 같은 관대한 파싱).
--                           board FK는 RESTRICT — 게시판은 소프트 삭제라 FK가 삭제를 막지 않고,
--                           게시판 삭제가 이 테이블 행을 함께 지운다(PR B).
--
-- DDL 2문 — 문마다 암묵 커밋. 실패 복구는 docs/migration-guide.md "V26~V28".
-- ============================================================

CREATE TABLE `board` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `public_yn` bit(1) NOT NULL,
  `attachment_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `member_board_permission` (
  `member_id` bigint(20) NOT NULL,
  `board_id` bigint(20) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`member_id`, `board_id`, `action`),
  KEY `idx_member_board_permission_board_id` (`board_id`),
  CONSTRAINT `fk_member_board_permission_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_member_board_permission_board` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
