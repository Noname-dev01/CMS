-- ============================================================
-- V3: 기본 사이드바 메뉴 시드 (2026-07-10)
--
-- MenuDataLoader(삭제됨)의 의미를 그대로 이관: menu 테이블이
-- "완전히 비었을 때만" 전체 시드하고, 행이 하나라도 있으면
-- 커스터마이즈된 기존 데이터로 간주해 절대 건드리지 않는다.
-- 기본 메뉴 "보충" 기능은 의도적으로 제공하지 않는다.
--
-- 구현 규칙 (계획 문서 3번 — Codex 리뷰 반영):
--  * 초기 상태를 세션 변수로 선두에서 캡처한다. 행 단위
--    (SELECT COUNT(*) FROM menu) = 0 조건은 첫 INSERT 직후부터
--    거짓이 되어 이후 행이 전부 스킵되는 함정이 있으므로 금지.
--  * 부모 menu_no를 고정값으로 가정하지 않는다 — 빈 테이블이라도
--    AUTO_INCREMENT가 1이 아닐 수 있다. LAST_INSERT_ID()로 캡처.
--  * DDL 금지(순수 DML만): MariaDB에서 DDL은 암묵적 커밋으로
--    실패 시 전체 롤백 보장을 깨뜨린다.
--
-- 원본: docs/migration/2026-07-09_seed_default_menus.sql
-- ============================================================

SET @menu_empty := ((SELECT COUNT(*) FROM menu) = 0);

-- 최상위 단일 메뉴
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '대시보드', '/admin', 'fas fa-fw fa-tachometer-alt', 1, 'ALL', 0, NULL, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '메뉴 관리', '/admin/menu/manage', 'fas fa-fw fa-bars', 1, 'ADMIN', 1, NULL, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

-- '회원 관리' 그룹 — 생성 직후 실제 menu_no를 캡처해 자식의 up_menu_no로 사용
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '회원 관리', NULL, 'fas fa-fw fa-user-shield', 1, 'ALL', 2, NULL, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

SET @member_group_id := LAST_INSERT_ID();

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '관리자 조회', '/admin/member/manage', 'fas fa-fw fa-users', 1, 'ADMIN', 0, @member_group_id, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '관리자 추가', '/admin/member/new', 'fas fa-fw fa-user-plus', 1, 'ADMIN', 1, @member_group_id, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '내 정보', '/admin/member/info', 'fas fa-fw fa-id-badge', 1, 'ALL', 2, @member_group_id, NOW(), NOW()
FROM DUAL WHERE @menu_empty;

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '활동 로그', '/admin/log/manage', 'fas fa-fw fa-clipboard-list', 1, 'ADMIN', 3, NULL, NOW(), NOW()
FROM DUAL WHERE @menu_empty;
