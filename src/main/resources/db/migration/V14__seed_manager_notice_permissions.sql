-- ============================================================
-- V14: MANAGER의 공지사항 권한 시드 — "일회성 초기화" (2026-10-02)
--
-- adversarial-review/plan/PLAN-menu-permission-management.md §4 (PR ①), 결정 U1(a).
--
-- 지금까지 SecurityConfig·@PreAuthorize가 MANAGER에게 열어 둔 공지사항 조회·생성·수정·삭제를
-- 그대로 DB 허용 행으로 옮긴다 — 이 PR 배포 직후에도 MANAGER의 접근 범위는 오늘과 같다.
--
-- ⚠️ 이 파일은 초기화 전용이다. Flyway는 성공한 마이그레이션을 다시 실행하지 않으며, 이미 성공한 뒤 수동으로
--    재실행하면 ADMIN이 회수한 권한을 되살린다. 누락·삭제된 권한의 복구는 권한관리 화면/API로만 한다.
--    WHERE NOT EXISTS는 부분 실패 후 재시도에서 중복 삽입을 막기 위한 것이다.
-- update_date는 NULL로 둔다(아직 한 번도 수정되지 않음 — DB NOW()와 앱 KST Clock의 시간대 혼용을 피한다).
-- ============================================================

INSERT INTO `permission_role` (`role`, `version`, `update_date`)
SELECT 'ROLE_MANAGER', 0, NULL
WHERE NOT EXISTS (SELECT 1 FROM `permission_role` WHERE `role` = 'ROLE_MANAGER');

INSERT INTO `role_permission` (`role`, `feature`, `action`)
SELECT 'ROLE_MANAGER', 'NOTICE', 'READ'
WHERE NOT EXISTS (SELECT 1 FROM `role_permission`
                  WHERE `role` = 'ROLE_MANAGER' AND `feature` = 'NOTICE' AND `action` = 'READ');

INSERT INTO `role_permission` (`role`, `feature`, `action`)
SELECT 'ROLE_MANAGER', 'NOTICE', 'CREATE'
WHERE NOT EXISTS (SELECT 1 FROM `role_permission`
                  WHERE `role` = 'ROLE_MANAGER' AND `feature` = 'NOTICE' AND `action` = 'CREATE');

INSERT INTO `role_permission` (`role`, `feature`, `action`)
SELECT 'ROLE_MANAGER', 'NOTICE', 'UPDATE'
WHERE NOT EXISTS (SELECT 1 FROM `role_permission`
                  WHERE `role` = 'ROLE_MANAGER' AND `feature` = 'NOTICE' AND `action` = 'UPDATE');

INSERT INTO `role_permission` (`role`, `feature`, `action`)
SELECT 'ROLE_MANAGER', 'NOTICE', 'DELETE'
WHERE NOT EXISTS (SELECT 1 FROM `role_permission`
                  WHERE `role` = 'ROLE_MANAGER' AND `feature` = 'NOTICE' AND `action` = 'DELETE');
