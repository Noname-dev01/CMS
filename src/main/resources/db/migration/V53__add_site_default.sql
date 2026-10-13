-- ============================================================
-- V53: site.default_yn 추가 (2026-10-13, PLAN-site-menu.md PR ② P2-5)
--
-- 접두어 없는 `/`가 이동할 기본 공개 사이트를 가리키는 표시 컬럼. NOT NULL DEFAULT 0 — 롤백한 이전 앱이 이 컬럼 없이 INSERT해도 호환된다.
-- 기본 사이트는 정확히 1개여야 하며, 그 불변식은 DB 제약이 아니라 서비스(모든 site 행 FOR UPDATE)와 시험이 지킨다.
-- 멱등: IF NOT EXISTS. DDL 단독 파일.
-- ============================================================

ALTER TABLE `site`
  ADD COLUMN IF NOT EXISTS `default_yn` bit(1) NOT NULL DEFAULT 0;
