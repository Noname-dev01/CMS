-- ------------------------------------------------------------
-- 게시판 표시 순서 + 게시글 상단 고정 (PLAN-board-definition.md "PR 3 상세 설계 — 표시 순서 + 상단 고정").
-- board.sort_order   : 관리 화면(게시판 목록·내 게시판 선택·권한관리 게시판 표)의 표시 순서. 작은 값이 먼저, 같으면 id 순. 서비스가 0..9999만 허용
-- board.pin_max_count: 공개 목록 1페이지 상단에 고정할 수 있는 글 수 상한. 0 = 고정 끔, 서비스가 0..5만 허용
-- post.pinned        : 상단 고정 여부. 고정 개수는 그 게시판의 미삭제 pinned=1 글 수(노출 여부·기간과 무관)
-- idx_post_board_pinned: 고정 개수 COUNT(게시판 FOR UPDATE를 쥔 채 실행)와 공개 고정 영역 조회를 고정 행 범위로 줄인다.
-- 모든 DEFAULT가 현행 동작(순서 0 = id 순, 고정 끔, 고정 아님)이라 기존 행·롤백한 이전 앱이 만든 행 모두 지금과 같게 동작한다
-- (업그레이드 경로 시험 BoardSortOrderPinUpgradeTest). 모든 절이 IF NOT EXISTS라 부분 적용 뒤 flyway repair 하고 다시 실행해도 된다.
-- ------------------------------------------------------------
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `sort_order` int(11) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS `pin_max_count` int(11) NOT NULL DEFAULT 0;

ALTER TABLE `post`
  ADD COLUMN IF NOT EXISTS `pinned` bit(1) NOT NULL DEFAULT b'0',
  ADD INDEX IF NOT EXISTS `idx_post_board_pinned` (`board_id`, `pinned`, `deleted`, `create_date`);
