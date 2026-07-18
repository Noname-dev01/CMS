-- 보안 정책 컬럼 — null fail-open 경로 차단 (V6 백필 성공 후에만 도달)
-- 단일 인스턴스 배포 전제: 마이그레이션 중 구버전 앱 공존 없음 (계획서 v6 참조)
ALTER TABLE `member`
  MODIFY COLUMN `password_changed_at` DATETIME(6) NOT NULL;
