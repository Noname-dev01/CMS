-- ============================================================
-- V57: 사이드바의 "메뉴 관리"를 "정적 페이지 관리"에 합친다 (데이터만 — 스키마 변경 없음) (2026-10-14)
--
-- adversarial-review/plan/PLAN-content-menu-unify.md "후속 PR — 사이드바 두 항목 통합" U2-2.
-- 두 항목은 같은 화면(정적 페이지 · 메뉴 · 사이트 관리)으로 들어간다. 살아남는 쪽은 /admin/site-page/manage 행이다 — 이 URL이
-- AdminFeature.SITE_PAGE의 사이드바 노출 판정 키라서 MANAGER에게 계속 보이고, /admin/menu/manage는 이미 그 화면으로 302되는 입구일 뿐이다.
--
-- 전부-아니면-전무: 관리자가 UI에서 손본 환경을 덮어쓰지 않도록, 적격 판정을 변경 전에 한 번만 내려 @merge_ok에 담고 UPDATE·DELETE가
-- 같은 값을 쓴다(V3의 @menu_empty와 같은 방식 — Flyway가 한 연결에서 문장을 순서대로 실행한다). 조건이 하나라도 어긋나면 두 행 모두 그대로다.
--   - 두 행 모두 관리자 사이트(site_id=1)에 같은 URL의 행이 정확히 1개이고, 시드 그대로의 이름·아이콘·설명(NULL)·활성·최상위이며 자식이 없다.
--   - 자식 조건은 두 행 모두에 건다: "정적 페이지 관리" 행에 자식이 생기면 그 행이 그룹이 돼 링크가 그려지지 않으므로 합치면 두 진입점이 모두 사라진다.
--   - 문자열은 utf8mb4_nopad_bin으로 비교한다(menu 컬럼은 utf8mb4_general_ci라 대소문자·후행 공백을 구분하지 못한다).
--   - 날짜(create_date·update_date)와 ord는 판정에 쓰지 않는다(V3 시드는 NOW(), V38은 NULL이고 드래그 재정렬은 보존 사유가 아니다).
-- 재실행하면 "메뉴 관리" 행이 없어 @merge_ok = 0이라 아무것도 하지 않는다(멱등). 신규 설치는 V3·V38이 두 행을 만든 뒤 합쳐 같은 결과가 된다.
--
-- DDL 금지(순수 DML만): V3·V38 등과 같은 규칙. DELETE에 같은 테이블 서브쿼리·별칭을 쓰지 않는다(MariaDB 10.11은 DELETE ... AS 별칭 미지원).
-- ============================================================

SET @merge_ok = (
    SELECT CASE WHEN
        (SELECT COUNT(*) FROM menu
          WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/menu/manage') = 1
        AND (SELECT COUNT(*) FROM menu
          WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage') = 1
        AND (SELECT COUNT(*) FROM menu a
          WHERE a.site_id = 1
            AND a.menu_url  COLLATE utf8mb4_nopad_bin = '/admin/menu/manage'
            AND a.menu_name COLLATE utf8mb4_nopad_bin = '메뉴 관리'
            AND a.menu_icon COLLATE utf8mb4_nopad_bin = 'fas fa-fw fa-bars'
            AND a.menu_desc IS NULL AND a.use_yn = 1 AND a.up_menu_no IS NULL
            AND NOT EXISTS (SELECT 1 FROM menu c WHERE c.up_menu_no = a.menu_no)) = 1
        AND (SELECT COUNT(*) FROM menu b
          WHERE b.site_id = 1
            AND b.menu_url  COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage'
            AND b.menu_name COLLATE utf8mb4_nopad_bin = '정적 페이지 관리'
            AND b.menu_icon COLLATE utf8mb4_nopad_bin = 'fas fa-fw fa-file-alt'
            AND b.menu_desc IS NULL AND b.use_yn = 1 AND b.up_menu_no IS NULL
            AND NOT EXISTS (SELECT 1 FROM menu c WHERE c.up_menu_no = b.menu_no)) = 1
    THEN 1 ELSE 0 END
);

UPDATE menu SET menu_name = '정적 페이지 · 메뉴 · 사이트 관리'
WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/site-page/manage' AND @merge_ok = 1;

DELETE FROM menu
WHERE site_id = 1 AND menu_url COLLATE utf8mb4_nopad_bin = '/admin/menu/manage' AND @merge_ok = 1;
