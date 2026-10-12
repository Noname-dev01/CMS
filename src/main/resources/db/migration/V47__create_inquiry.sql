-- ============================================================
-- V47: 문의(inquiry) 테이블 (2026-10-12)
--
-- 공개 문의 폼 → 관리자 문의함(adversarial-review/plan/PLAN-inquiry.md 쟁점 6).
--
-- 개인정보(이름·이메일·본문)를 담는다. 방문자 IP·User-Agent는 저장하지 않는다(레이트리밋은 메모리 버킷만 쓴다).
-- status는 RECEIVED(접수) / IN_PROGRESS(처리 중) / ANSWERED(답변 완료) 문자열이고 전이는 제한하지 않는다.
-- consent_at은 개인정보 수집·이용 동의 시각이다. 하드 삭제라 deleted 컬럼이 없다.
-- idx_inquiry_status_id : 상태 탭 목록(status = ? ORDER BY id DESC)과 미처리 건수 상한(status <> 'ANSWERED') COUNT.
--
-- DDL 1문. 실패 복구는 docs/migration-guide.md "V47~V48".
-- ============================================================

CREATE TABLE `inquiry` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `email` varchar(100) NOT NULL,
  `title` varchar(100) NOT NULL,
  `body` text NOT NULL,
  `status` varchar(20) NOT NULL,
  `consent_at` datetime(6) NOT NULL,
  `create_date` datetime(6) NOT NULL,
  `update_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inquiry_status_id` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
