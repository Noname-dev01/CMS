-- ============================================================
-- V16: menu.access_role 컬럼 제거 (2026-10-02)
--
-- 권한관리 PR 4/4 (PLAN-menu-permission-management.md §8, U6). 사이드바 노출은 PR 2/4부터 권한 판정기(코드 카탈로그 ∩ role_permission)에서
-- 도출되고, Menu 엔티티는 이 컬럼을 매핑하지 않는다. 컬럼에는 인덱스·제약이 없다(V1).
--
-- 되돌릴 수 없다 — 컬럼 값이 사라지고 PR 1/4 이전(access_role을 매핑하는) 코드는 ddl-auto: validate로 기동에 실패한다.
-- 실행 전 DB 백업이 필수다(docs/migration-guide.md "V16 배포 전 백업과 복구", docs/deployment.md).
--
-- V2·V3·V9는 이 컬럼을 쓰지만 항상 V16보다 먼저 실행되므로 신규 DB도 안전하다(머지된 마이그레이션은 수정하지 않는다).
-- IF EXISTS: 수동으로 이미 지운 환경에서 이 SQL을 다시 실행해도 실패하지 않는다(SQL 멱등). 단 Flyway가 실패(success=0)로 기록한 뒤의 복구는
--            별도다 — 단순 재기동은 거부되고 flyway repair가 필요하다(docs/migration-guide.md "V16 실패 복구").
--
-- DDL 단독 파일(MariaDB DDL은 암묵 커밋 — DML과 섞지 않는다, V13과 같은 규칙).
-- ============================================================

ALTER TABLE `menu` DROP COLUMN IF EXISTS `access_role`;
