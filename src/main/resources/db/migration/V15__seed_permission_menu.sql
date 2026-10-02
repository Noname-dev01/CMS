-- ============================================================
-- V15: 권한 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-02)
--
-- 권한관리 PR 3/4 (PLAN-menu-permission-management.md §6, PLAN-permission-management-pr3.md §5-4).
-- 최상위 맨 끝에 둔다. access_role은 PR 2/4부터 엔티티가 매핑하지 않으므로 지정하지 않는다(DEFAULT NULL).
-- 노출은 카탈로그(PERMISSION = ADMIN_ONLY)가 정한다 — MANAGER에게는 보이지 않는다.
--
-- ord: 최상위 MAX(ord)+1. menu.ord는 signed INT라 MAX가 상한이면 +1이 넘쳐 마이그레이션이 실패하므로 LEAST로 묶는다 —
--      표시 순서가 (ord, menu_no)라 ord가 같아도 더 큰 menu_no를 가진 새 행이 맨 끝에 놓인다.
-- create_date·update_date는 NULL — DB NOW()와 앱 KST Clock의 시간대 혼용을 피한다(V14와 같은 이유, 메뉴 화면은 날짜를 표시하지 않는다).
-- 메뉴를 지운 뒤 Flyway가 이 파일을 다시 실행하지는 않는다(시드 메뉴 영구삭제와 같은 규칙).
--
-- DDL 금지(순수 DML만): MariaDB에서 DDL은 암묵적 커밋으로 실패 시 전체 롤백 보장을 깨뜨린다(V3·V9·V14와 동일 규칙).
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '권한 관리', '/admin/permission/manage', 'fas fa-fw fa-user-lock', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/permission/manage');
