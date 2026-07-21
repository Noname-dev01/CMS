-- ============================================================
-- V9: 공지사항 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-07-20)
--
-- V3(빈 테이블 전용 시드)와 달리, 이 마이그레이션은 menu 테이블에
-- 기존 데이터가 있어도 공지사항 메뉴 URL이 아직 없으면 삽입한다.
-- 신규 콘텐츠 도메인 배포 시 사이드바 메뉴가 환경마다 수동 등록
-- 없이 자동으로 나타나도록 보장한다
-- (사용자 승인 2026-07-20 — 로드맵의 "메뉴 등록은 데이터로(API 호출)"
-- 원 결정에서 편차).
--
-- DDL 금지(순수 DML만): MariaDB에서 DDL은 암묵적 커밋으로 실패 시
-- 전체 롤백 보장을 깨뜨린다(V3와 동일 규칙).
-- ============================================================

INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
SELECT '공지사항 관리', '/admin/notice/manage', 'fas fa-fw fa-bullhorn', 1, 'ALL', 4, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/notice/manage');
