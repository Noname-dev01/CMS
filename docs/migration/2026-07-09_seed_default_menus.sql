-- 기본 사이드바 메뉴 시드 (dev 외 환경용)
--
-- 배경: 사이드바가 정적 HTML에서 menu 테이블 기반 동적 렌더링으로 전환됐다.
-- MenuDataLoader는 @Profile("dev") 전용이므로, menu 테이블이 빈 non-dev 환경에
-- 배포하면 사이드바에 브랜드/토글 외 내비게이션이 전혀 없다. (Codex 리뷰 P2 지적)
-- 이 스크립트는 그런 환경에서 기본 메뉴를 시드한다. 구조는 MenuDataLoader와 동일하게 유지할 것.
--
-- 멱등성: menu_url(그룹은 menu_name) 기준 NOT EXISTS 가드로 재실행에 안전하다.
-- Flyway 도입 시 repeatable migration(R__seed_default_menus.sql)으로 이관한다.

-- 최상위 단일 메뉴
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '대시보드', '/admin', 'fas fa-fw fa-tachometer-alt', 1, 'ALL', 0, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin');

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 'ADMIN', 1, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin/menu/manage');

-- 그룹 (menu_url이 없으므로 최상위 + 이름으로 식별)
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '회원 관리', NULL, 'fas fa-fw fa-user-shield', 1, 'ALL', 2, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_name = '회원 관리' AND m.up_menu_no IS NULL);

-- 그룹 하위 (부모는 이름으로 조회)
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '관리자 조회', '/admin/member/manage', 'fas fa-fw fa-users', 1, 'ADMIN', 0,
       (SELECT m2.menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리' AND up_menu_no IS NULL LIMIT 1) m2),
       NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin/member/manage');

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '관리자 추가', '/admin/member/new', 'fas fa-fw fa-user-plus', 1, 'ADMIN', 1,
       (SELECT m2.menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리' AND up_menu_no IS NULL LIMIT 1) m2),
       NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin/member/new');

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '내 정보', '/admin/member/info', 'fas fa-fw fa-id-badge', 1, 'ALL', 2,
       (SELECT m2.menu_no FROM (SELECT menu_no FROM menu WHERE menu_name = '회원 관리' AND up_menu_no IS NULL LIMIT 1) m2),
       NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin/member/info');

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '활동 로그', '/admin/log/manage', 'fas fa-fw fa-clipboard-list', 1, 'ADMIN', 3, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM menu m WHERE m.menu_url = '/admin/log/manage');
