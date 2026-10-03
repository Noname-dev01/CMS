-- ============================================================
-- V18: MANAGER 회원 개별 허용 행 테이블 (2026-10-03)
--
-- adversarial-review/plan/PLAN-member-permission.md §5-A.
--
-- 행이 있으면 허용이고 거부 행은 없다. ADMIN 행은 두지 않는다(코드 고정).
-- feature·action은 DB enum이 아닌 VARCHAR다 — 카탈로그에 기능을 추가할 때 ALTER가 필요 없고, 코드에서 지운 기능의 행이
-- 남아도 앱이 무시한다(V13과 같은 이유). 회원 하드 삭제 경로가 없어 ON DELETE 동작은 두지 않는다.
-- DDL 1문. 암묵 커밋으로 인한 이력 불일치 복구는 docs/migration-guide.md "V17~V19 실패 복구".
-- ============================================================

CREATE TABLE `member_permission` (
  `member_id` bigint(20) NOT NULL,
  `feature` varchar(50) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`member_id`, `feature`, `action`),
  CONSTRAINT `fk_member_permission_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
