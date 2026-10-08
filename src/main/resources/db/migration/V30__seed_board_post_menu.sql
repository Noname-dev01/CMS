-- ============================================================
-- V30: 게시글 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-08)
--
-- 범용 게시판 PR B(adversarial-review/plan/PLAN-board.md 쟁점 11). 최상위 맨 끝에 둔다.
-- 노출은 카탈로그(BOARD = BOARD_SCOPED)가 정한다 — ADMIN은 항상, MANAGER는 어느 게시판에서든 조회 권한이 있을 때 보인다.
-- ord·create_date·update_date 규칙은 V15·V28과 같다(LEAST로 INT 상한 방어, 날짜는 NULL).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15·V28과 같은 규칙.
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '게시글 관리', '/admin/board/posts', 'fas fa-fw fa-file-alt', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/board/posts');
