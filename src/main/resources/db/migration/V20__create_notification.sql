-- ============================================================
-- V20: 관리자 알림(notification) 테이블 (2026-10-05)
--
-- adversarial-review/plan/PLAN-admin-notification.md §4.
--
-- 알림은 그 회원 본인에게만 보이며(D11 열람 필터는 조회 쿼리 조건) message는 서버가 상수 템플릿과 enum 라벨로 조립한다.
-- dedupe_key는 같은 사건의 중복 생성 방지용이다(E3: 비밀번호 주기당 1건) — NULL이면 유니크 제약 대상이 아니다.
-- member FK는 ON DELETE CASCADE다: 앱은 회원을 하드 삭제하지 않지만(DELETED 상태 전이만), 회원 행을 직접 지우는 시험·운영 SQL이
-- 알림 때문에 조용히 실패하지 않게 한다(계획서 R-2 — member_permission은 RESTRICT지만 알림은 회원 없이 의미가 없는 종속 데이터다).
-- 인덱스: idx_notification_member_id = 목록(member_id = ? AND id < ? ORDER BY id DESC LIMIT ?),
--         idx_notification_member_read = 미읽음 수(read_at IS NULL)·90일 정리(read_at < ?).
-- DDL 1문. 암묵 커밋으로 인한 이력 불일치 복구는 docs/migration-guide.md "V20 실패 복구".
-- ============================================================

CREATE TABLE `notification` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `type` varchar(40) NOT NULL,
  `message` varchar(255) NOT NULL,
  `link_url` varchar(255) DEFAULT NULL,
  `dedupe_key` varchar(100) DEFAULT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_member_dedupe` (`member_id`, `dedupe_key`),
  KEY `idx_notification_member_id` (`member_id`, `id`),
  KEY `idx_notification_member_read` (`member_id`, `read_at`, `id`),
  CONSTRAINT `fk_notification_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
