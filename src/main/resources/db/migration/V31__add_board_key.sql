-- ============================================================
-- V31: 게시판 시스템 키(board_key) 컬럼 (2026-10-09)
--
-- 공지사항을 "공지" 게시판으로 흡수하는 ①-2(adversarial-review/plan/PLAN-notice-to-board.md 쟁점 1).
-- 공개 /notices는 URL에 게시판 ID가 없으므로 서버가 "어느 게시판이 공지인가"를 알아야 한다. 환경마다 달라지는
-- auto-increment ID나 ADMIN이 바꿀 수 있는 이름 대신, 시스템 게시판에만 고정 키를 준다(공지 게시판 = 'NOTICE').
-- NULL = 일반 게시판. UNIQUE라 같은 키의 게시판이 둘이 될 수 없다(MariaDB UNIQUE는 NULL 중복을 허용한다).
--
-- DDL 단독 마이그레이션 — 문마다 암묵 커밋이라 DML(V32)과 섞지 않는다. 실패 복구는 docs/migration-guide.md "V31~V32".
-- ============================================================

ALTER TABLE `board`
  ADD COLUMN `board_key` varchar(30) DEFAULT NULL,
  ADD UNIQUE KEY `uk_board_board_key` (`board_key`);
