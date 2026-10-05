-- ============================================================
-- V21: 관리자 쪽지 테이블 3종 (2026-10-05)
--
-- adversarial-review/plan/PLAN-admin-message.md §4 (적대적 리뷰 7라운드 ship).
--
-- admin_message             : 1:1 쪽지. 보내는 쪽·받는 쪽이 각자 보관함에서 삭제 시각(sender_deleted_at·recipient_deleted_at)으로
--                             지우고, 양쪽 모두 지우면 물리 삭제한다(D8). 제목·본문은 사용자 입력이라 서버는 원문을 그대로 저장한다.
-- admin_message_sender_state: 발신자별 잠금 전용 행(내용 없음). 발송 트랜잭션의 첫 문장이 이 행을 만들며 잠가 같은 발신자의 발송만
--                             직렬화한다 — 회원 행을 잠그면 INSERT의 FK 공유 잠금과 맞물려 A→B/B→A 동시 발송이 교착한다(R1-1).
-- admin_message_send_log    : 발송 이력(발신자·시각만). 쪽지 삭제와 무관하게 24시간 빈도 한도를 계산한다(R1-3).
--
-- 외래키는 모두 RESTRICT(기본)다: 회원 행을 직접 지우는 SQL이 상대 보관함의 쪽지를 조용히 지우지 못한다(R1-13).
-- 앱은 회원을 하드 삭제하지 않는다(DELETED 상태 전이).
-- 인덱스에 삭제 열을 포함한 이유: 무기한 보관에서 한쪽만 삭제된 행이 쌓여도 목록·미읽음 수가 삭제된 행을 대량 스캔하지 않게 한다(R1-11).
-- DDL 3문. MariaDB DDL은 문마다 암묵 커밋이라 중간에 중단되면 일부 테이블만 남을 수 있다 — 복구는 docs/migration-guide.md "V21 실패 복구"
-- (성공 이력이 없고 기대 스키마이며 세 테이블이 모두 비어 있을 때만 DROP).
-- ============================================================

CREATE TABLE `admin_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20) NOT NULL,
  `recipient_id` bigint(20) NOT NULL,
  `title` varchar(100) NOT NULL,
  `body` varchar(2000) NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `sender_deleted_at` datetime(6) DEFAULT NULL,
  `recipient_deleted_at` datetime(6) DEFAULT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_inbox` (`recipient_id`, `recipient_deleted_at`, `id`),
  KEY `idx_admin_message_unread` (`recipient_id`, `recipient_deleted_at`, `read_at`, `id`),
  KEY `idx_admin_message_sent` (`sender_id`, `sender_deleted_at`, `id`),
  CONSTRAINT `fk_admin_message_sender` FOREIGN KEY (`sender_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_admin_message_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `member` (`id`),
  CONSTRAINT `ck_admin_message_not_self` CHECK (`sender_id` <> `recipient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `admin_message_sender_state` (
  `member_id` bigint(20) NOT NULL,
  PRIMARY KEY (`member_id`),
  CONSTRAINT `fk_admin_message_state_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `admin_message_send_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20) NOT NULL,
  `sent_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_send_log` (`sender_id`, `sent_at`),
  CONSTRAINT `fk_admin_message_send_log_member` FOREIGN KEY (`sender_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
