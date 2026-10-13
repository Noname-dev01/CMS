-- ============================================================
-- V51: 기본 사이트 시드 2행 (2026-10-13, PLAN-site-menu.md D1)
--
-- 고정 ID로 넣는다 — id=1 관리자 사이트(menu.site_id DEFAULT 1과 사이드바 상수가 의존), id=2 기본 공개 사이트('main').
-- 이후 AUTO_INCREMENT는 3부터. 재실행해도 늘지 않는다(WHERE NOT EXISTS). 시각은 NULL로 둔다(V34 선례 — DB NOW()를 쓰지 않는다).
-- 순수 DML.
-- ============================================================

INSERT INTO site (id, site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date)
SELECT 1, 'admin', '관리자', 'ADMIN', 0, 1, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM site WHERE id = 1);

INSERT INTO site (id, site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date)
SELECT 2, 'main', '기본 사이트', 'PUBLIC', 1, 1, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM site WHERE id = 2);
