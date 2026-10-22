-- ============================================================
-- V2: 기준 시드 데이터 (2026-10-21)
--
-- 실배포 전에 V1~V58의 시드·데이터 변환 결과를 하나로 합친 것이다(adversarial-review/plan/PLAN-migration-squash.md).
-- 옛 체인을 적용한 DB의 행을 id까지 그대로 옮겼다 — menu_no의 빈 번호(2·8)도 옛 체인에서 지워진 행의 흔적이고, 명시 id로
-- 넣어 다음 AUTO_INCREMENT(site 3, board 2, menu 22)까지 옛 체인과 같다.
-- 시각 칸은 옛 시드의 형태를 그대로 따른다 — 초기 메뉴(대시보드·회원 관리·활동 로그)는 NOW(), 나중에 추가된 메뉴·사이트·
-- 공지 게시판은 NULL이다. NOW()는 DB 시각이라 ClockUsageConventionTest 밖의 알려진 한계다(루트 CLAUDE.md).
-- 순수 DML.
-- ============================================================

-- 사이트: id=1 관리자 사이트(menu.site_id DEFAULT 1과 사이드바 상수가 의존), id=2 기본 공개 사이트('main', `/`의 이동 대상).
INSERT INTO site (id, site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date, default_yn) VALUES
  (1, 'admin', '관리자',     'ADMIN',  b'0', b'1', NULL, NULL, b'0'),
  (2, 'main',  '기본 사이트', 'PUBLIC', b'1', b'1', NULL, NULL, b'1');

-- 관리자 사이드바 메뉴(site_id=1). 노출은 메뉴가 아니라 권한 판정기가 정한다(MenuVisibility) — 위임 불가 기능의 메뉴는
-- MANAGER에게 보이지 않는다. '사이트 관리'(19)는 링크 없는 그룹이고 정적 페이지·메뉴·사이트 설정이 그 아래에 있다.
INSERT INTO menu (menu_no, create_date, menu_desc, menu_icon, menu_name, menu_url, ord, up_menu_no, update_date, use_yn, site_id) VALUES
  ( 1, NOW(), NULL, 'fas fa-fw fa-tachometer-alt',      '대시보드',         '/admin',                   0, NULL, NOW(), b'1', 1),
  ( 3, NOW(), NULL, 'fas fa-fw fa-user-shield',         '회원 관리',        NULL,                       2, NULL, NOW(), b'1', 1),
  ( 4, NOW(), NULL, 'fas fa-fw fa-users',               '관리자 조회',      '/admin/member/manage',     0, 3,    NOW(), b'1', 1),
  ( 5, NOW(), NULL, 'fas fa-fw fa-user-plus',           '관리자 추가',      '/admin/member/new',        1, 3,    NOW(), b'1', 1),
  ( 6, NOW(), NULL, 'fas fa-fw fa-id-badge',            '내 정보',          '/admin/member/info',       2, 3,    NOW(), b'1', 1),
  ( 7, NOW(), NULL, 'fas fa-fw fa-clipboard-list',      '활동 로그',        '/admin/log/manage',        3, NULL, NOW(), b'1', 1),
  ( 9, NULL,  NULL, 'fas fa-fw fa-user-lock',           '권한 관리',        '/admin/permission/manage', 5, NULL, NULL,  b'1', 1),
  (10, NULL,  NULL, 'fas fa-fw fa-columns',             '게시판 관리',      '/admin/board/manage',      6, NULL, NULL,  b'1', 1),
  (11, NULL,  NULL, 'fas fa-fw fa-file-alt',            '게시글 관리',      '/admin/board/posts',       7, NULL, NULL,  b'1', 1),
  (12, NULL,  NULL, 'fas fa-fw fa-images',              '배너 관리',        '/admin/banner/manage',     8, NULL, NULL,  b'1', 1),
  (13, NULL,  NULL, 'fas fa-fw fa-user-clock',          '세션 관리',        '/admin/session/manage',    9, NULL, NULL,  b'1', 1),
  (14, NULL,  NULL, 'fas fa-fw fa-file-alt',            '정적 페이지 관리', '/admin/site-page/manage',  0, 19,   NULL,  b'1', 1),
  (15, NULL,  NULL, 'fas fa-fw fa-window-restore',      '팝업 관리',        '/admin/popup/manage',      11, NULL, NULL, b'1', 1),
  (16, NULL,  NULL, 'fas fa-fw fa-question-circle',     'FAQ 관리',         '/admin/faq/manage',        12, NULL, NULL, b'1', 1),
  (17, NULL,  NULL, 'fas fa-fw fa-images',              '미디어 라이브러리', '/admin/media/manage',     13, NULL, NULL, b'1', 1),
  (18, NULL,  NULL, 'fas fa-fw fa-envelope-open-text',  '문의함',           '/admin/inquiry/manage',    14, NULL, NULL, b'1', 1),
  (19, NULL,  NULL, 'fas fa-fw fa-sitemap',             '사이트 관리',      NULL,                       10, NULL, NULL, b'1', 1),
  (20, NULL,  NULL, 'fas fa-fw fa-bars',                '메뉴 관리',        '/admin/menu/manage',       1, 19,   NULL,  b'1', 1),
  (21, NULL,  NULL, 'fas fa-fw fa-cog',                 '사이트 설정',      '/admin/site/manage',       2, 19,   NULL,  b'1', 1);

-- 공통 공지 게시판(시스템 게시판 board_key='NOTICE'). 공개·첨부 허용, 모든 공개 사이트 공통(site_id NULL).
INSERT INTO board (id, name, public_yn, attachment_yn, deleted, create_date, update_date, board_key, site_id) VALUES
  (1, '공지사항', b'1', b'1', b'0', NULL, NULL, 'NOTICE', NULL);

-- 단일 행 가드·카운터. 앱이 FOR UPDATE로 잠그는 대상이라 반드시 있어야 한다.
INSERT INTO banner_lock (id) VALUES (1);
INSERT INTO faq_lock (id, version) VALUES (1, 0);
INSERT INTO content_image_usage (id, total_bytes, total_count) VALUES (1, 0, 0);
