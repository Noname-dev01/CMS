-- =====================================================================
-- [이관됨 — 2026-07-10 Flyway 도입]
-- 컬럼 정의는 V1__init_schema.sql로, 백필은 V2__backfill_menu_access_role.sql
-- (방어적 ALTER + 3단계 백필: 알려진 ADMIN → 알려진 ALL 명시 → 나머지
-- ADMIN fail-closed)로 이관됐다. 이 파일은 이력 보존용이며 직접 실행하지 않는다.
-- =====================================================================
-- 메뉴 사이드바 노출 범위 컬럼 추가
-- dev 환경은 ddl-auto: update가 nullable 컬럼으로 자동 추가한다.
-- 애플리케이션은 null을 ALL(공용)로 정규화하므로 컬럼 추가만으로 동작은 하지만,
-- 기존 데이터가 있는 환경에서는 아래 백필을 반드시 함께 실행해야 한다.
-- (미백필 시: 이전 정적 사이드바에서 ROLE_ADMIN 뒤에 숨겨져 있던 링크가
--  MANAGER 사이드바에 노출된다. 서버는 403으로 차단하지만 UI 회귀다 — Codex 리뷰 P2 지적)

ALTER TABLE menu
    ADD COLUMN access_role VARCHAR(20) NULL COMMENT '사이드바 노출 범위 (ALL=공용, ADMIN=관리자 전용)';

-- ── 백필 (기존 행이 있는 환경에서는 필수, 순서 중요) ──────────────────────

-- 1) ADMIN 전용 화면을 가리키는 레거시 행을 먼저 ADMIN으로 지정
--    (이전 정적 사이드바에서 sec:authorize hasRole('ROLE_ADMIN')이었던 경로들)
UPDATE menu
SET access_role = 'ADMIN'
WHERE access_role IS NULL
  AND (menu_url LIKE '/admin/menu%'
    OR menu_url LIKE '/admin/log%'
    OR menu_url = '/admin/member/manage'
    OR menu_url = '/admin/member/new');

-- 2) 나머지 레거시 행은 공용으로 확정
UPDATE menu SET access_role = 'ALL' WHERE access_role IS NULL;
