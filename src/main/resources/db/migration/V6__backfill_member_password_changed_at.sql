-- 기존 계정 백필: 마이그레이션 시각 기준 전원 90일 유예
-- (update_date는 토큰 발급·잠금 해제로도 갱신되는 오염된 대리값이라 사용하지 않음.
--  DB 세션이 UTC면 KST 대비 최대 9시간 이르지만 90일 지평에서 수용)
-- WHERE ... IS NULL: 부분 실패 후 재실행 멱등성 보강
UPDATE `member`
SET `password_changed_at` = NOW(6)
WHERE `password_changed_at` IS NULL;
