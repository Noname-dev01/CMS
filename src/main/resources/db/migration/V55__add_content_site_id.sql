-- ============================================================
-- V55: 콘텐츠 사이트 소속 site_id + 낡은 화면 검사 version (2026-10-13, PLAN-site-menu.md PR ③)
--
-- 배너·팝업·FAQ 카테고리·정적 페이지에 소속 사이트(site_id)를 붙인다. NULL = 모든 공개 사이트 공통(기본값) — 기존 행은 그대로 공통이고,
-- 롤백한 이전 앱이 site_id 없이 INSERT해도 공통으로 들어간다. FK는 ON DELETE RESTRICT(소속 콘텐츠가 있는 사이트는 지울 수 없다).
-- 공개 사이트(PUBLIC)만 가리킬 수 있다는 규칙은 DB 제약이 아니라 서비스(ContentSiteScope)가 검증한다.
--
-- banner.version / faq_category.version: 수정 요청의 낡은 화면 검사용(정적 페이지 site_page.version과 같은 취지) — 수정(replace)마다 +1.
-- 팝업은 수정마다 오르는 revision(V39)을 그대로 쓰고, 정적 페이지는 이미 version이 있어 컬럼을 더하지 않는다.
--
-- 롤백 주의: 사이트 전용(site_id IS NOT NULL) 콘텐츠가 하나라도 있으면 이 마이그레이션 이전 앱으로 롤백하지 않는다(이전 앱은 site_id를
-- 무시해 전용 콘텐츠가 모든 사이트에 노출된다). 롤백 전에 전용 콘텐츠를 삭제하거나 백업을 복구한다 — site_id를 NULL로 바꾸는 것은
-- 삭제 대안이 아니다(공통 공개가 된다). FAQ는 항목 → 카테고리 순으로 지운다(docs/deployment.md).
-- 멱등: 컬럼·FK 모두 IF NOT EXISTS — 실패 뒤 flyway repair로 재실행해도 실패하지 않는다. DDL 4문(문마다 암묵 커밋), DML과 섞지 않는다.
-- ============================================================

ALTER TABLE `banner`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `version` int(11) NOT NULL DEFAULT 1,
  ADD CONSTRAINT `fk_banner_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;

ALTER TABLE `popup`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) DEFAULT NULL,
  ADD CONSTRAINT `fk_popup_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;

ALTER TABLE `faq_category`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `version` int(11) NOT NULL DEFAULT 1,
  ADD CONSTRAINT `fk_faq_category_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;

ALTER TABLE `site_page`
  ADD COLUMN IF NOT EXISTS `site_id` bigint(20) DEFAULT NULL,
  ADD CONSTRAINT `fk_site_page_site` FOREIGN KEY IF NOT EXISTS (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;
