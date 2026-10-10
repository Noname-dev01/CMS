-- ============================================================
-- V34: 배너 가드 행 + 배너 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-09)
--
-- adversarial-review/plan/PLAN-public-home-banner.md 쟁점 5·15.
-- 가드 행(id=1)은 앱이 FOR UPDATE로 잠그는 대상이라 반드시 있어야 한다(없으면 배너 생성·삭제·순서 저장이 IllegalStateException).
-- 메뉴는 최상위 맨 끝에 둔다. 노출은 카탈로그(BANNER = DELEGABLE)가 정한다 — ADMIN은 항상, MANAGER는 BANNER 조회 권한이 있을 때 보인다.
-- ord·create_date·update_date 규칙은 V15·V28·V30과 같다(LEAST로 INT 상한 방어, 날짜는 NULL).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15·V28·V30과 같은 규칙.
-- ============================================================

INSERT INTO banner_lock (id)
SELECT 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM banner_lock WHERE id = 1);

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '배너 관리', '/admin/banner/manage', 'fas fa-fw fa-images', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/banner/manage');
