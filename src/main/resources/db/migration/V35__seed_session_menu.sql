-- ============================================================
-- V35: 세션 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-10)
--
-- adversarial-review/plan/PLAN-session-management.md 쟁점 10.
-- 메뉴는 최상위 맨 끝에 둔다. 노출은 카탈로그가 정한다 — SESSION은 ADMIN_ONLY라 ADMIN에게만 보인다.
-- ord·create_date·update_date 규칙은 V15·V28·V30·V34와 같다(LEAST로 INT 상한 방어, 날짜는 NULL).
-- 테이블 변경 없음(스키마 변경 없음).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15·V28·V30·V34와 같은 규칙.
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '세션 관리', '/admin/session/manage', 'fas fa-fw fa-user-clock', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/session/manage');
