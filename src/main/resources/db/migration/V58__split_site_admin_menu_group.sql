-- ============================================================
-- V58: 사이드바의 "정적 페이지 · 메뉴 · 사이트 관리" 한 항목을 "사이트 관리" 그룹 아래 세 항목으로 나눈다 (데이터만 — 스키마 변경 없음) (2026-10-16)
--
-- adversarial-review/plan/PLAN-content-menu-unify.md "후속 PR 2" U3-3(개정 U3-v2). V57이 합친 항목을 되돌린다:
-- 탭 화면이 세 화면(정적 페이지 관리 /admin/site-page/manage · 메뉴 관리 /admin/menu/manage · 사이트 설정 /admin/site/manage)이 됐다.
--
-- 전부-아니면-전무: 적격 판정을 변경 전에 한 번만 내려 @group_ok에 담고 모든 문장이 같은 값을 쓴다(V57의 @merge_ok와 같은 방식 —
-- Flyway가 한 연결에서 문장을 순서대로 실행하고, DML만이라 한 트랜잭션으로 묶여 중간에 실패하면 모두 되돌아간다). 적격 조건(관리자 사이트 site_id=1):
--   - URL /admin/site-page/manage 행이 정확히 1개이고 활성·최상위이며 자식이 없다(자식이 달렸으면 그 행이 그룹이라 옮기지 않는다).
--   - URL /admin/menu/manage·/admin/site/manage 행이 하나도 없다(있으면 중복이 된다).
--   - 이름이 "사이트 관리"인 행이 없다(관리자가 같은 이름의 그룹을 이미 만들었거나 이 마이그레이션이 이미 적용된 경우).
--   - 이름·아이콘·설명은 판정에 쓰지 않는다 — 관리자가 V57 결과 행을 손봤어도 진입점 세 개는 있어야 하므로, 손본 값은 보존한 채 옮기기만 한다.
--     다만 이름이 V57 시드 그대로("정적 페이지 · 메뉴 · 사이트 관리")일 때만 "정적 페이지 관리"로 되돌린다.
--   - 문자열은 utf8mb4_nopad_bin으로 비교한다(menu 컬럼은 utf8mb4_general_ci라 대소문자·후행 공백을 구분하지 못한다).
-- 적격하지 않으면 아무것도 바뀌지 않는다(재실행 멱등 포함). 신규 설치는 V3·V38·V57이 순서대로 돈 뒤 이 마이그레이션이 같은 결과를 만든다.
--
-- LAST_INSERT_ID: 그룹 INSERT 직후 @group_no에 담는다. INSERT가 0행이면 LAST_INSERT_ID()는 이전 값이라 @group_ok = 1일 때만 쓰고,
-- 이후 모든 문장에 @group_ok = 1 조건이 있어 이전 세션 값을 부모로 쓰는 일이 없다.
--
-- 롤백 참고(운영 배포 전이라 시험하지 않는다 — docs/migration-guide.md): 그룹과 "메뉴 관리"·"사이트 설정" 행을 URL·부모로 식별해 지운다.
-- 그룹에 관리자가 추가한 다른 자식이 있으면 그룹을 지우지 않고 자식을 최상위로 올린다. 한 트랜잭션으로 실행한다. V57·V58 파일은 수정하지 않는다.
--
-- DDL 금지(순수 DML만): V3·V38·V57과 같은 규칙.
-- ============================================================

SET @group_ok = (
    SELECT CASE WHEN
        (SELECT COUNT(*) FROM menu
          WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage') = 1
        AND (SELECT COUNT(*) FROM menu a
          WHERE a.site_id = 1
            AND a.menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage'
            AND a.use_yn = 1 AND a.up_menu_no IS NULL
            AND NOT EXISTS (SELECT 1 FROM menu c WHERE c.up_menu_no = a.menu_no)) = 1
        AND (SELECT COUNT(*) FROM menu
          WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin IN ('/admin/menu/manage', '/admin/site/manage')) = 0
        AND (SELECT COUNT(*) FROM menu
          WHERE site_id = 1 AND menu_name COLLATE utf8mb4_nopad_bin = '사이트 관리') = 0
    THEN 1 ELSE 0 END
);

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '사이트 관리', NULL, 'fas fa-fw fa-sitemap', 1, a.ord, NULL, NULL, NULL
FROM menu a
WHERE a.site_id = 1 AND a.menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage' AND @group_ok = 1;

SET @group_no = IF(@group_ok = 1, LAST_INSERT_ID(), NULL);

UPDATE menu
SET up_menu_no = @group_no,
    ord = 0,
    menu_name = CASE WHEN menu_name COLLATE utf8mb4_nopad_bin = '정적 페이지 · 메뉴 · 사이트 관리'
                     THEN '정적 페이지 관리' ELSE menu_name END
WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage' AND @group_ok = 1;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 1, @group_no, NULL, NULL
FROM DUAL WHERE @group_ok = 1;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '사이트 설정', '/admin/site/manage', 'fas fa-fw fa-cog', 1, 2, @group_no, NULL, NULL
FROM DUAL WHERE @group_ok = 1;
