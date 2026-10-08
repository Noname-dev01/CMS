-- ============================================================
-- V28: 게시판 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-08)
--
-- 범용 게시판 PR A(adversarial-review/plan/PLAN-board.md 쟁점 11). 최상위 맨 끝에 둔다.
-- 노출은 카탈로그(BOARD_ADMIN = ADMIN_ONLY)가 정한다 — MANAGER에게는 보이지 않는다.
-- ord·create_date·update_date 규칙은 V15와 같다(LEAST로 INT 상한 방어, 날짜는 NULL).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15와 같은 규칙.
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '게시판 관리', '/admin/board/manage', 'fas fa-fw fa-columns', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/board/manage');
