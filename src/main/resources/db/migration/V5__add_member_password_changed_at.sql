-- 비밀번호 최종 변경 시각 (90일 도달 시 PASSWORD_EXPIRED 전이 기준)
-- ALTER 1개만 수행 — MariaDB ALTER TABLE은 암묵 커밋이라 파일당 커밋 단위 1개로 격리
ALTER TABLE `member`
  ADD COLUMN `password_changed_at` DATETIME(6) NULL;
