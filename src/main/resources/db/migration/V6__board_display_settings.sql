-- ------------------------------------------------------------
-- 게시판 표시·식별 설정 + 낡은 화면 검출 버전 (PLAN-board-definition.md §4 · "PR 1 상세 설계 확인").
-- description    : 공개 목록 머리의 설명(평문, 출력은 th:text). NULL = 없음
-- list_page_size : 공개 목록 한 페이지 글 수(서비스가 10·20·30·50만 허용)
-- author_label   : 공개 화면의 작성자 표시명(게시판 단위 고정값 — 로그인 ID를 공개하지 않는다). NULL = 표시 안 함
-- show_view_count: 공개 화면에 조회수 표시 여부
-- new_badge_days : 새 글 배지 기간(일, 0 = 끔, 서비스가 0..30만 허용)
-- writable_yn    : 쓰기 허용(0 = 쓰기 중지·보관 — 새 글·수정·첨부·본문 이미지 업로드 차단, 공개 노출은 public_yn이 따로 정한다)
-- version        : 게시판 변경마다 +1. 수정·삭제 요청의 값과 다르면 409(정적 페이지·배너·팝업과 같은 방식, 1부터 시작)
-- 모든 DEFAULT가 현행 동작이라 기존 행·롤백한 이전 앱이 만든 행 모두 지금과 같게 보인다(업그레이드 경로 시험 BoardDisplaySettingsUpgradeTest).
-- ------------------------------------------------------------
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `description` varchar(500) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `list_page_size` int(11) NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS `author_label` varchar(50) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `show_view_count` bit(1) NOT NULL DEFAULT b'0',
  ADD COLUMN IF NOT EXISTS `new_badge_days` int(11) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS `writable_yn` bit(1) NOT NULL DEFAULT b'1',
  ADD COLUMN IF NOT EXISTS `version` int(11) NOT NULL DEFAULT 1;
