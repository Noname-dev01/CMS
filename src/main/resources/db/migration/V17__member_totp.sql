-- 관리자 2단계 인증(TOTP) (2026-11-14, adversarial-review/plan/PLAN-totp-two-factor.md 결정 7, 이슈 #251)
-- 새 테이블 2개만 만든다 — 기존 행에 영향이 없다(행 없음 = 2단계 미사용).
-- 비밀값은 member 컬럼이 아니라 별도 테이블에 둔다: CustomUserDetails가 Member 엔티티 전체를 세션·레지스트리에 보관하므로
-- member 컬럼이면 암호문이 principal에 실린다.
-- secret: AES-256-GCM 암호문('v1:' + Base64(IV‖암호문), AAD = member_totp:{member_id}). 키는 CMS_TOTP_ENCRYPTION_KEY.
-- last_used_step: 마지막으로 통과한 30초 스텝 — 같은 코드 재사용 차단.
-- failed_attempts: TOTP 전용 실패 횟수 — 비밀번호 재로그인·재설정이 member.failed_login_count를 0으로 만들어도 유지된다.
-- 회원 FK는 ON DELETE CASCADE(앱에는 회원 하드 삭제 경로가 없고, 시험 정리의 회원 삭제가 막히지 않게 한다).
CREATE TABLE IF NOT EXISTS `member_totp` (
  `member_id` bigint(20) NOT NULL,
  `secret` varchar(255) NOT NULL,
  `enabled_at` datetime(6) NOT NULL,
  `last_used_step` bigint(20) NOT NULL,
  `failed_attempts` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`member_id`),
  CONSTRAINT `fk_member_totp_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 복구 코드: 120비트 무작위(Base32 24자)의 SHA-256 16진 문자열. 원문은 등록 응답에서 한 번만 보여 준다.
CREATE TABLE IF NOT EXISTS `member_totp_recovery_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `code_hash` char(64) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_totp_recovery_code_member` (`member_id`),
  CONSTRAINT `fk_member_totp_recovery_code_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
