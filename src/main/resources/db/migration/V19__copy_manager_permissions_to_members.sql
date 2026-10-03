-- ============================================================
-- V19: 배포 시점의 ROLE_MANAGER 허용 행을 기존 MANAGER 회원 전원에게 복사 — "일회성 초기화" (2026-10-03)
--
-- adversarial-review/plan/PLAN-member-permission.md §5-A.
--
-- 이 파일 배포 직후에도 기존 MANAGER 계정의 접근 범위는 오늘과 같다. 이후 생성되는 MANAGER는 권한이 없다(0개).
--   - 정확 일치(BINARY)·카탈로그 유효 행만 복사한다: 대소문자·후행 공백 변형 행(read, 'READ ')과 모르는 행은 판정기가 무시하던
--     것이라 옮기지 않는다. 현재 카탈로그의 위임 가능 기능은 NOTICE 하나이고 마이그레이션은 코드 카탈로그를 읽을 수 없어 리터럴로 쓴다.
--   - READ 없는 쓰기 행은 판정기가 거부하던 것이라 NOTICE READ 행이 있을 때만 복사한다(없으면 아무것도 복사하지 않는다).
--   - DELETED 회원은 제외한다(수정 불가 종단 상태).
--   - NOT EXISTS는 부분 실패 후 재시도에서 중복 삽입을 막기 위한 것이다.
--
-- ⚠️ 이 파일은 초기화 전용이다. Flyway는 성공한 마이그레이션을 다시 실행하지 않으며, 성공한 뒤 수동으로 재실행하면
--    ADMIN이 회원별로 회수한 권한을 되살린다(V14와 같은 경고). 누락·삭제된 권한의 복구는 권한관리 화면/API로만 한다.
-- ============================================================

INSERT INTO `member_permission` (`member_id`, `feature`, `action`)
SELECT m.`id`, rp.`feature`, rp.`action`
FROM `member` m
JOIN `role_permission` rp ON BINARY rp.`role` = 'ROLE_MANAGER'
WHERE m.`user_type` = 'ROLE_MANAGER'
  AND m.`status` <> 'DELETED'
  AND BINARY rp.`feature` = 'NOTICE'
  AND BINARY rp.`action` IN ('READ', 'CREATE', 'UPDATE', 'DELETE')
  AND EXISTS (SELECT 1 FROM `role_permission` r
              WHERE BINARY r.`role` = 'ROLE_MANAGER' AND BINARY r.`feature` = 'NOTICE' AND BINARY r.`action` = 'READ')
  AND NOT EXISTS (SELECT 1 FROM `member_permission` mp
                  WHERE mp.`member_id` = m.`id` AND mp.`feature` = rp.`feature` AND mp.`action` = rp.`action`);
