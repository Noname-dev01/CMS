package com.cms.admin.member.totp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * TOTP 비밀값 암호화(AES-256-GCM, 12바이트 무작위 IV, 128비트 태그) — DB·백업이 유출돼도 2단계가 유지되게 한다
 * (PLAN-totp-two-factor.md 결정 6).
 *
 * <p>AAD는 {@code member_totp:{memberId}}라 다른 회원 행으로 옮겨 붙인 암호문은 복호되지 않는다.
 * 키는 Base64로 정확히 32바이트여야 하며, 아니면 생성이 실패한다(prod 기동 실패 — 키 없이 2단계를 조용히 쓰지 못하게 한다).
 *
 * <p><b>키 교체</b>(PLAN-totp-key-rotation.md): 옛 키를 하나 더 받아 복호에만 쓴다. 쓰기 형식은 {@code v2:{키ID}:} + Base64(IV‖암호문‖태그)다
 * (2단계 배포의 2단계, 이슈 #269 — DB만 보고 행별 키를 가릴 수 있게). 키ID는 원시 키 SHA-256의 앞 8바이트 16진 소문자 16자다.
 * {@code v1:}(키ID 없음, #269 이전 쓰기 형식)은 읽기만 한다. <b>복호 코드는 #274(키 교체 1단계)와 같다</b> — 그 버전으로 롤백해도 {@code v2}가 읽힌다.
 * 그보다 이전 버전은 {@code v2}를 읽지 못한다.
 */
public class TotpSecretCipher {

    private static final String V1_PREFIX = "v1:";
    private static final String V2_PREFIX = "v2:";
    private static final int KEY_ID_HEX_LENGTH = 16;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec currentKey;
    private final String currentKeyId;
    private final SecretKeySpec previousKey;
    private final String previousKeyId;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(String base64Key) {
        this(base64Key, null);
    }

    /**
     * @param base64Key         현재 키(필수) — 암호화와 복호에 쓴다
     * @param previousBase64Key 옛 키(선택, null·공백이면 없음) — 복호에만 쓴다. 형식이 틀리거나 현재 키와 같으면 생성 실패
     */
    public TotpSecretCipher(String base64Key, String previousBase64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("TOTP 암호화 키(cms.totp.encryption-key)가 비어 있습니다.");
        }
        byte[] current = decodeKey(base64Key, "TOTP 암호화 키");
        this.currentKey = new SecretKeySpec(current, "AES");
        this.currentKeyId = keyIdOf(current);
        if (previousBase64Key == null || previousBase64Key.isBlank()) {
            this.previousKey = null;
            this.previousKeyId = null;
        } else {
            byte[] previous = decodeKey(previousBase64Key, "TOTP 옛 암호화 키(cms.totp.previous-encryption-key)");
            String id = keyIdOf(previous);
            if (id.equals(currentKeyId)) {
                throw new IllegalArgumentException("TOTP 옛 암호화 키가 현재 키와 같습니다 — 교체가 끝났으면 옛 키 설정을 비우세요.");
            }
            this.previousKey = new SecretKeySpec(previous, "AES");
            this.previousKeyId = id;
        }
    }

    private static byte[] decodeKey(String base64Key, String name) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + "는 Base64여야 합니다.");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(name + "는 Base64로 32바이트여야 합니다.");
        }
        return raw;
    }

    private static String keyIdOf(byte[] rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey);
            return HexFormat.of().formatHex(digest, 0, KEY_ID_HEX_LENGTH / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    /** 현재 키ID — 로그에 남겨 운영자가 보관 기록과 대조한다. */
    public String currentKeyId() {
        return currentKeyId;
    }

    public Optional<String> previousKeyId() {
        return Optional.ofNullable(previousKeyId);
    }

    /** 항상 현재 키로 {@code v2:{현재 키ID}:} 형식을 쓴다. */
    public String encrypt(long memberId, byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(memberId));
            byte[] sealed = cipher.doFinal(secret);
            return currentPrefix() + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 비밀값 암호화에 실패했습니다.", e);
        }
    }

    /** 현재 키 또는 옛 키로 복호한다. */
    public byte[] decrypt(long memberId, String stored) {
        return decrypt(memberId, stored, true);
    }

    /** 현재 키로만 복호한다 — 재암호화 대상 판정과 교체 완료 검증용. */
    public byte[] decryptWithCurrentKey(long memberId, String stored) {
        return decrypt(memberId, stored, false);
    }

    /**
     * 현재 쓰기 형식인가 — {@code v2:{현재 키ID}:}로 시작하고(Java 비교, 대소문자 구분) 현재 키로 인증 복호된다.
     * 재암호화 대상 판정과 교체 완료 검증의 기준이다(접두어만 보면 손상 행을, 복호만 보면 현재 키 {@code v1} 행을 놓친다).
     */
    public boolean isCurrent(long memberId, String stored) {
        if (stored == null || !stored.startsWith(currentPrefix())) {
            return false;
        }
        try {
            Arrays.fill(decryptWithCurrentKey(memberId, stored), (byte) 0);
            return true;
        } catch (DecryptionException e) {
            return false;
        }
    }

    private String currentPrefix() {
        return V2_PREFIX + currentKeyId + ":";
    }

    private byte[] decrypt(long memberId, String stored, boolean allowPrevious) {
        if (stored == null) {
            throw new DecryptionException();
        }
        if (stored.startsWith(V1_PREFIX)) {
            byte[] payload = decodePayload(stored.substring(V1_PREFIX.length()));
            try {
                return open(currentKey, memberId, payload);
            } catch (DecryptionException e) {
                if (!allowPrevious || previousKey == null) {
                    throw e;
                }
                return open(previousKey, memberId, payload);
            }
        }
        if (stored.startsWith(V2_PREFIX)) {
            int idEnd = V2_PREFIX.length() + KEY_ID_HEX_LENGTH;
            if (stored.length() <= idEnd || stored.charAt(idEnd) != ':') {
                throw new DecryptionException();
            }
            String keyId = stored.substring(V2_PREFIX.length(), idEnd);
            SecretKeySpec key;
            if (keyId.equals(currentKeyId)) {
                key = currentKey;
            } else if (allowPrevious && keyId.equals(previousKeyId)) {
                key = previousKey;
            } else {
                throw new DecryptionException();
            }
            return open(key, memberId, decodePayload(stored.substring(idEnd + 1)));
        }
        throw new DecryptionException();
    }

    private static byte[] decodePayload(String base64) {
        try {
            byte[] all = Base64.getDecoder().decode(base64);
            if (all.length <= IV_BYTES) {
                throw new DecryptionException();
            }
            return all;
        } catch (IllegalArgumentException e) {
            throw new DecryptionException();
        }
    }

    private static byte[] open(SecretKeySpec key, long memberId, byte[] all) {
        try {
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
