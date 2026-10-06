-- ============================================================
-- V22: 역할 단위 권한 테이블 role_permission·permission_role 제거 (2026-10-06)
--
-- 권한관리 사용자별 전환 PR B (PLAN-member-permission.md "PR B 계획"). PR A(V17~V19)부터 권한은 회원 단위(member_permission)이고
-- 앱은 두 테이블을 매핑·조회하지 않는다. 당시 값은 V19가 기존 MANAGER 회원에게 이미 복사했다.
--
-- 되돌릴 수 없다 — 값이 사라지고 PR A 이전(RolePermission을 매핑하는) 코드는 ddl-auto: validate로 기동에 실패한다.
-- 실행 전 DB 백업이 필수다(docs/migration-guide.md "V22 배포 전 백업과 복구").
--
-- V13·V14·V19가 두 테이블을 쓰지만 항상 V22보다 먼저 실행되므로 신규 DB도 안전하다(머지된 마이그레이션은 수정하지 않는다).
-- role_permission이 FK로 permission_role을 참조하므로 자식부터 지운다.
-- IF EXISTS: 첫 DROP이 커밋된 뒤 중단돼도(MariaDB DDL은 문마다 암묵 커밋) 같은 SQL을 다시 실행할 수 있다. Flyway가 실패(success=0)로
--            기록한 뒤에는 flyway repair가 필요하다(docs/migration-guide.md "V22 실패 복구").
--
-- DDL 단독 파일(DML과 섞지 않는다, V13·V16과 같은 규칙).
-- ============================================================

DROP TABLE IF EXISTS `role_permission`;
DROP TABLE IF EXISTS `permission_role`;
