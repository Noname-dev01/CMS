-- ============================================================
-- V40: 팝업 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-12)
--
-- adversarial-review/plan/PLAN-popup.md 쟁점 2·3.
-- 메뉴는 최상위 맨 끝에 둔다. 노출은 카탈로그(POPUP = DELEGABLE)가 정한다 — ADMIN은 항상, MANAGER는 POPUP 조회 권한이 있을 때 보인다.
-- ord·create_date·update_date 규칙은 V15·V28·V30·V34·V35·V38과 같다(LEAST로 INT 상한 방어, 날짜는 NULL).
-- 테이블 변경 없음(스키마 변경은 V39).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15·V28·V30·V34·V35·V38과 같은 규칙.
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '팝업 관리', '/admin/popup/manage', 'fas fa-fw fa-window-restore', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/popup/manage');
