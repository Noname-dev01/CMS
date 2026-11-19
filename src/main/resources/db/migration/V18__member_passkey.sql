-- 관리자 패스키(WebAuthn) — 2단계 인증의 대체 수단 (2026-11-19, adversarial-review/plan/PLAN-passkey-second-factor.md 결정 3, 이슈 #259)
-- 새 테이블 2개만 만든다 — 기존 행에 영향이 없다(행 없음 = 패스키 없음). 이전 앱은 이 테이블을 모른다(무시).
-- member 컬럼이 아니라 별도 테이블: CustomUserDetails가 Member 엔티티 전체를 세션·레지스트리에 보관하므로(V17과 같은 이유).
-- 모든 쓰기는 member 행 잠금(findByIdForUpdate) 아래에서 한다.

-- WebAuthn user handle: 회원당 1개, 무작위 바이트(개인정보 없음). 인증기에 저장돼 PR2(패스키 단독 로그인)에서 회원을 찾는다.
CREATE TABLE IF NOT EXISTS `member_passkey_user` (
  `member_id` bigint(20) NOT NULL,
  `user_handle` varbinary(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `uk_member_passkey_user_handle` (`user_handle`),
  CONSTRAINT `fk_member_passkey_user_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 등록된 자격 증명(공개키). credential_id는 WebAuthn 상한 1023바이트.
-- 유효 조건: created_at >= member_totp.enabled_at (현재 TOTP 등록 이후에 만든 것만 — 결정 1).
-- attestation_object는 검증 라이브러리(Spring Security 7.0.7 Webauthn4JRelyingPartyOperations.authenticate)가 인증 때 읽는다.
CREATE TABLE IF NOT EXISTS `member_passkey` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `credential_id` varbinary(1023) NOT NULL,
  `public_key` blob NOT NULL,
  `signature_count` bigint(20) NOT NULL,
  `uv_initialized` bit(1) NOT NULL,
  `backup_eligible` bit(1) NOT NULL,
  `backup_state` bit(1) NOT NULL,
  `transports` varchar(255) NOT NULL,
  `attestation_object` blob NOT NULL,
  `attestation_client_data_json` blob NOT NULL,
  `label` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_passkey_credential` (`credential_id`),
  KEY `idx_member_passkey_member` (`member_id`),
  CONSTRAINT `fk_member_passkey_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
