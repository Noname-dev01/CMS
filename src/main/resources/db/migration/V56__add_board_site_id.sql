-- ============================================================
-- V56: 게시판 사이트 소속 site_id (2026-10-14, PLAN-site-menu.md PR ④)
--
-- 게시판에 소속 사이트(site_id)를 붙인다. NULL = 모든 공개 사이트 공통(기본값) — 기존 행은 그대로 공통이고, 롤백한 이전 앱이
-- site_id 없이 INSERT해도 공통으로 들어간다. FK는 ON DELETE RESTRICT(소속 게시판이 있는 사이트는 지울 수 없다).
-- 공개 사이트(PUBLIC)만 가리킬 수 있다는 규칙은 DB 제약이 아니라 서비스(ContentSiteScope)가 검증한다.
--
-- 사이트별 공지 게시판은 board_key = 'NOTICE:{siteId}'로 구분한다(공통 공지는 기존 'NOTICE'). 기존 UNIQUE(board_key)가 사이트당 하나를 보장하므로
-- 인덱스 변경은 없다.
--
-- 롤백 주의: 사이트 전용(site_id IS NOT NULL) 게시판이 하나라도 있으면 이 마이그레이션 이전 앱으로 롤백하지 않는다(이전 앱은 site_id를
-- 무시해 전용 게시판이 모든 사이트에 노출된다). site_id를 NULL로 바꾸는 것은 삭제 대안이 아니다(공통 공개가 된다).
-- 멱등: 컬럼·FK 모두 IF NOT EXISTS — 실패 뒤 flyway repair로 재실행해도 실패하지 않는다. DDL 단독 파일.
-- ============================================================

ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) DEFAULT NULL,
  ADD CONSTRAINT `fk_board_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;
