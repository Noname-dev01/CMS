-- ============================================================
-- V12: admin_action_log 테이블에 대상 이름 스냅샷(target_label) 컬럼 추가 (2026-10-01)
--
-- 하드 삭제처럼 대상 행이 사라지는 액션은 target_id만으로는 사후에 무엇을
-- 지웠는지 알 수 없다. 대상의 이름 등을 로그 시점의 문자열로 남긴다
-- (adversarial-review/plan/PLAN-menu-permanent-delete.md 설계 §5, PR A).
--
-- NULL 허용 — 기존 행과 라벨을 지정하지 않는 액션은 NULL을 유지한다.
-- 길이는 엔티티(@Column(length = 500))와 일치해야 ddl-auto: validate를 통과한다.
-- ============================================================

ALTER TABLE `admin_action_log`
  ADD COLUMN `target_label` VARCHAR(500) DEFAULT NULL;
