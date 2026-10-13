-- ============================================================
-- V54: 기본 공개 사이트 지정 (2026-10-13, PLAN-site-menu.md PR ② P2-5)
--
-- 시드 공개 사이트 'main'(id=2)을 기본으로 지정한다. 기본 사이트가 이미 있으면 건드리지 않는다(재실행 안전).
-- MariaDB는 UPDATE 대상 테이블을 서브쿼리에서 직접 읽을 수 없어 파생 테이블로 한 번 감싼다. 순수 DML.
-- ============================================================

UPDATE site
SET default_yn = 1
WHERE id = 2
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM site WHERE default_yn = 1) d);
