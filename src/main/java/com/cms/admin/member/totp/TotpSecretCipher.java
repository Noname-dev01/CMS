package com.cms.admin.member.totp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP 비밀값 암호화(AES-256-GCM, 12바이트 무작위 IV, 128비트 태그) — DB·백업이 유출돼도 2단계가 유지되게 한다
 * (PLAN-totp-two-factor.md 결정 6).
 *
 * <p>AAD는 {@code member_totp:{memberId}}라 다른 회원 행으로 옮겨 붙인 암호문은 복호되지 않는다.
 * 저장 형식은 {@code v1:} + Base64(IV‖암호문‖태그). 키는 Base64로 정확히 32바이트여야 하며, 아니면 생성이 실패한다
 * (prod 기동 실패 — 키 없이 2단계를 조용히 쓰지 못하게 한다).
 */
public class TotpSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("TOTP 암호화 키(cms.totp.encryption-key)가 비어 있습니다.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TOTP 암호화 키는 Base64여야 합니다.");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("TOTP 암호화 키는 Base64로 32바이트여야 합니다.");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(long memberId, byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(memberId));
            byte[] sealed = cipher.doFinal(secret);
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 비밀값 암호화에 실패했습니다.", e);
        }
    }

    public byte[] decrypt(long memberId, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new DecryptionException();
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new DecryptionException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(aad(memberId));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 원인 예외를 싣지 않는다 — 키·암호문 단서를 로그에 남기지 않기 위해
            throw new DecryptionException();
        }
    }

    private static byte[] aad(long memberId) {
        return ("member_totp:" + memberId).getBytes(StandardCharsets.US_ASCII);
    }

    /** 복호 실패(키 불일치·변조·다른 회원 행·형식 오류). 메시지는 상수다. */
    public static class DecryptionException extends RuntimeException {
        public DecryptionException() {
            super("TOTP 비밀값을 복호할 수 없습니다.");
        }
    }
}
