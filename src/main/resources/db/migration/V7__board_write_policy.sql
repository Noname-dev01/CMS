-- ------------------------------------------------------------
-- 게시판별 작성 정책 (PLAN-board-definition.md "PR 2 상세 설계 — 작성 정책"). 전역 상한(AttachmentFilePolicy)이 천장이고 게시판은 그 이하로만 줄인다.
-- attach_max_count  : 게시글당 첨부 개수 상한(서비스가 1..5만 허용)
-- attach_max_size_mb: 첨부 파일당 크기 상한(MB, 서비스가 1..10만 허용)
-- attach_extensions : 허용 확장자 — 소문자·중복 제거·사전순·쉼표 구분 정규형만 저장. NULL = 전역 화이트리스트 전체(전체를 고르면 NULL로 정규화)
-- body_image_yn     : 본문 이미지 허용(0 = 새 이미지 거부 — 이미 참조 중인 이미지는 유지)
-- publish_period_yn : 예약 게시 허용(0 = 새 기간 설정 거부 — 기존 기간은 계속 유효, 비우기는 허용)
-- 모든 DEFAULT가 현행 동작이라 기존 행·롤백한 이전 앱이 만든 행 모두 지금과 같게 동작한다(업그레이드 경로 시험 BoardWritePolicyUpgradeTest).
-- ------------------------------------------------------------
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `attach_max_count` int(11) NOT NULL DEFAULT 5,
  ADD COLUMN IF NOT EXISTS `attach_max_size_mb` int(11) NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS `attach_extensions` varchar(200) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `body_image_yn` bit(1) NOT NULL DEFAULT b'1',
  ADD COLUMN IF NOT EXISTS `publish_period_yn` bit(1) NOT NULL DEFAULT b'1';
