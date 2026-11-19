package com.cms.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 패스키 시험 도우미 — 실제 인증기 없이 {@code member_passkey} 행을 JDBC로 넣는다(공개키 검증 자체는 브라우저 가상 인증기 시험이 고정한다).
 * 회원 행이 지워지면 두 테이블 행은 FK CASCADE로 함께 지워진다.
 */
public final class PasskeyTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasskeyTestSupport() {
    }

    /** 회원의 핸들 행이 없으면 만든다. 핸들 바이트를 돌려준다. */
    public static byte[] ensureHandle(JdbcTemplate jdbc, long memberId) {
        List<byte[]> existing = jdbc.query("SELECT user_handle FROM member_passkey_user WHERE member_id = ?",
                (rs, i) -> rs.getBytes(1), memberId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        byte[] handle = new byte[32];
        RANDOM.nextBytes(handle);
        jdbc.update("INSERT INTO member_passkey_user (member_id, user_handle, created_at) VALUES (?, ?, NOW(6))", memberId, handle);
        return handle;
    }

    /** 패스키 행 하나를 넣고 자격 ID를 돌려준다. */
    public static byte[] insert(JdbcTemplate jdbc, long memberId, String label, LocalDateTime createdAt) {
        ensureHandle(jdbc, memberId);
        byte[] credentialId = new byte[16];
        RANDOM.nextBytes(credentialId);
        jdbc.update("INSERT INTO member_passkey (member_id, credential_id, public_key, signature_count, uv_initialized, backup_eligible,"
                        + " backup_state, transports, attestation_object, attestation_client_data_json, label, created_at)"
                        + " VALUES (?, ?, X'00', 0, 0, 0, 0, 'internal', X'00', X'00', ?, ?)",
                memberId, credentialId, label, createdAt);
        return credentialId;
    }

    public static int count(JdbcTemplate jdbc, long memberId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM member_passkey WHERE member_id = ?", Integer.class, memberId);
    }

    /** 회원의 TOTP 등록 시각 */
    public static LocalDateTime totpEnabledAt(JdbcTemplate jdbc, long memberId) {
        return jdbc.queryForObject("SELECT enabled_at FROM member_totp WHERE member_id = ?", LocalDateTime.class, memberId);
    }
}
