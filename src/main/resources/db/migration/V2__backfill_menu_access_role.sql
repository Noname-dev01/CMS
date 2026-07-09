-- ============================================================
-- V2: menu.access_role 기존 행 백필 (2026-07-10)
--
-- baseline 전략상 기존 DB는 V1을 실행하지 않으므로, 기존 행 데이터
-- 보정은 V2 이상에 두어야 기존 DB에 도달한다.
-- 미백필 시: 이전 정적 사이드바에서 ROLE_ADMIN 뒤에 숨겨져 있던 링크가
-- MANAGER 사이드바에 노출된다(서버는 403으로 차단하지만 UI 회귀).
--
-- 멱등성: 모든 UPDATE가 access_role IS NULL 조건 — 빈 DB와
-- 백필 완료된 DB에서는 no-op이다.
-- 원본: docs/migration/2026-07-09_add_menu_access_role.sql
-- ============================================================

-- 방어적 ALTER: access_role 컬럼이 없는 기존 DB(수동 SQL 미적용 + 최근
-- 앱 미기동)가 baseline되면 V1의 컬럼 정의를 영구히 건너뛰어 아래
-- UPDATE가 unknown column으로 실패한다. 컬럼이 이미 있으면 no-op.
-- 타입은 V1(= Hibernate 실물)과 동일한 enum으로 맞춰 드리프트를 막는다.
ALTER TABLE menu ADD COLUMN IF NOT EXISTS `access_role` enum('ADMIN','ALL') DEFAULT NULL;

-- ── 백필 3단계 (순서 중요) ──────────────────────────────────

-- 1) 알려진 ADMIN 전용 URL 패턴 → ADMIN
--    (이전 정적 사이드바에서 sec:authorize hasRole('ROLE_ADMIN')이었던 경로들)
UPDATE menu
SET access_role = 'ADMIN'
WHERE access_role IS NULL
  AND (menu_url LIKE '/admin/menu%'
    OR menu_url LIKE '/admin/log%'
    OR menu_url = '/admin/member/manage'
    OR menu_url = '/admin/member/new');

-- 2) 알려진 공용 메뉴 → ALL 명시 지정
--    (대시보드, 내 정보, '회원 관리' 그룹 — 3단계 fail-closed에 휩쓸려
--     MANAGER 사이드바에서 사라지지 않도록 반드시 catch-all보다 먼저 실행)
UPDATE menu
SET access_role = 'ALL'
WHERE access_role IS NULL
  AND (menu_url = '/admin'
    OR menu_url = '/admin/member/info'
    OR (menu_name = '회원 관리' AND up_menu_no IS NULL));

-- 3) 어느 패턴에도 안 걸린 미지의 레거시 행 → ADMIN (fail-closed)
--    미분류 행은 MANAGER에게 노출되는 방향보다 숨는 방향이 안전하다.
--    access_role은 노출 제어일 뿐 실제 차단은 Security(403)가 담당하며,
--    필요 시 메뉴 관리 화면에서 ALL로 복구 가능하다.
UPDATE menu
SET access_role = 'ADMIN'
WHERE access_role IS NULL;
