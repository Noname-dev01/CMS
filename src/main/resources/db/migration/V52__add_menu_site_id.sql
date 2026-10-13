-- ============================================================
-- V52: menu.site_id 추가 (2026-10-13, PLAN-site-menu.md D1)
--
-- NOT NULL DEFAULT 1(관리자 사이트): 기존 메뉴 행은 전부 관리자 사이트로 채워지고, 롤백한 이전 앱이 site_id 없이 INSERT해도
-- 관리자 메뉴로 들어간다. FK가 site_id 인덱스를 만든다. ON DELETE RESTRICT — 메뉴가 있는 사이트는 지울 수 없다.
--
-- 롤백 주의: 공개 사이트(site_id<>1) 메뉴가 하나라도 있으면 이 마이그레이션 이전 앱으로 롤백할 수 없다(이전 앱은 모든 행을
-- 한 트리로 읽고 이동·재활성화를 허용한다). 롤백 전에 공개 사이트 메뉴를 영구삭제하거나 백업을 복구한다(docs/deployment.md).
-- 멱등: 컬럼·FK 모두 IF NOT EXISTS — 실패 뒤 flyway repair로 재실행해도(FK가 이미 있어도) 실패하지 않는다(SiteMigrationTest).
-- DDL 단독 파일.
-- ============================================================

ALTER TABLE `menu`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) NOT NULL DEFAULT 1,
  ADD CONSTRAINT `fk_menu_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;
