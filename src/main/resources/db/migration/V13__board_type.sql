-- ------------------------------------------------------------
-- 게시판 유형 (PLAN-board-definition.md "PR 6 상세 설계 — 게시판 유형").
-- board.board_type: 공개 목록의 모양. LIST(목록형, 현행) / GALLERY(대표 이미지 격자) / WEBZINE(대표 이미지 + 요약).
-- 값 검증은 서비스가 한다(CHECK 제약 없음 — 다음 유형을 더할 때 마이그레이션이 필요 없게 한다). 알 수 없는 값은 읽을 때 LIST로 본다.
-- DEFAULT 'LIST'가 현행 동작이라 기존 행·롤백한 이전 앱이 만든 행 모두 지금과 같게 보인다(업그레이드 경로 시험 BoardTypeUpgradeTest).
-- IF NOT EXISTS라 부분 적용 뒤 flyway repair 하고 다시 실행해도 된다.
-- ------------------------------------------------------------
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `board_type` varchar(20) NOT NULL DEFAULT 'LIST';
