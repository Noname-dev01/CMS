-- ============================================================
-- V13: 권한관리(MANAGER 위임 권한) 테이블 2개 추가 (2026-10-02)
--
-- adversarial-review/plan/PLAN-menu-permission-management.md §4 (PR ①).
--
--   permission_role  권한 매트릭스를 가진 역할의 기준 행 + 낙관적 버전.
--                    허용 행이 하나도 없는 빈 집합에서도 잠글 대상과 버전이 있어야 한다.
--                    지금은 ROLE_MANAGER 한 행만 둔다(V14가 시드).
--   role_permission  역할이 허용받은 (기능, 동작). 행이 있으면 허용이고 거부 행은 없다.
--                    ADMIN 행은 두지 않는다(코드 고정).
--
-- role·feature·action은 DB enum이 아닌 VARCHAR다 — 카탈로그에 기능을 추가할 때 ALTER가
-- 필요 없고, 코드에서 지운 기능의 행이 남아도 앱이 무시한다.
-- MariaDB DDL은 암묵 커밋이므로 DDL(V13)과 시드 DML(V14)을 분리한다(V3·V9 규칙).
-- ============================================================

CREATE TABLE `permission_role` (
  `role` varchar(30) NOT NULL,
  `version` bigint(20) NOT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `role_permission` (
  `role` varchar(30) NOT NULL,
  `feature` varchar(50) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`role`, `feature`, `action`),
  CONSTRAINT `fk_role_permission_role` FOREIGN KEY (`role`) REFERENCES `permission_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
