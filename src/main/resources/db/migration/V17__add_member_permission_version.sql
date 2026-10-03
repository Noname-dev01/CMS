-- ============================================================
-- V17: 회원별 권한 매트릭스의 낙관적 버전 컬럼 (2026-10-03)
--
-- adversarial-review/plan/PLAN-member-permission.md §5-A.
--
-- 권한관리를 역할별에서 사용자별(MANAGER 개별)로 전환한다. 허용 행이 하나도 없는 회원도 잠글 대상(회원 행)과
-- 버전이 있어야 하므로 member에 버전을 둔다. 버전은 JPA @Version이 아니라 잠금 아래 수동 비교다.
-- DDL 1문 — 파일 안에서 일부만 성공하는 일은 없다. 단 MariaDB DDL은 암묵 커밋이라 DDL 커밋 후 Flyway 이력 기록 전에
-- 중단되면 실제 스키마와 이력이 어긋날 수 있다(복구: docs/migration-guide.md "V17~V19 실패 복구").
-- ============================================================

ALTER TABLE `member` ADD COLUMN `permission_version` bigint(20) NOT NULL DEFAULT 0;
