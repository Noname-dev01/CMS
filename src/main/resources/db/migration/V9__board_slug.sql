-- ------------------------------------------------------------
-- 게시판 슬러그 (PLAN-board-definition.md "PR 4a 상세 설계" D1).
-- board.slug: 관리자가 정한 공개 주소 슬러그. NULL = 기본 슬러그 board-{id}(저장하지 않고 id에서 계산한다).
--   기본값에서 한 번만 바꿀 수 있고(NULL → 값) 이후 불변이며, 소프트 삭제도 비우지 않는다 — 삭제된 게시판이 UNIQUE를
--   계속 쥐어 한 번 쓴 슬러그가 영구 예약된다(MariaDB UNIQUE는 NULL 중복을 허용한다).
-- 이전 앱은 이 컬럼을 모르고 INSERT해도 NULL(= 기본 슬러그)이라 그대로 동작한다. 저장된 링크 치환은 V10(Java)이 따로 한다 —
-- DDL은 암묵 커밋이라 같은 마이그레이션에 두면 치환 실패 시 롤백되지 않는다.
-- 모든 절이 IF NOT EXISTS라 부분 적용 뒤 flyway repair 하고 다시 실행해도 된다.
-- ------------------------------------------------------------
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `slug` varchar(50) DEFAULT NULL,
  ADD UNIQUE KEY IF NOT EXISTS `uk_board_slug` (`slug`);
