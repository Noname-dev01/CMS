-- 로그인 연속 실패 카운트 (5회 도달 시 LOCKED 자동 전이)
-- locked_at: 자동 잠금 시각 (30분 후 자동 해제 판정 기준). 수동 잠금(PATCH)은 null 유지 → 자동 해제 대상 아님
ALTER TABLE `member`
  ADD COLUMN `failed_login_count` INT NOT NULL DEFAULT 0,
  ADD COLUMN `locked_at` DATETIME(6) DEFAULT NULL;
