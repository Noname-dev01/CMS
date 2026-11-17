package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpSecretCipherTest {

    private static final byte[] KEY_BYTES = new byte[32];
    private static final byte[] OTHER_KEY_BYTES = filled(32, (byte) 7);
    private static final String KEY = Base64.getEncoder().encodeToString(KEY_BYTES);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(OTHER_KEY_BYTES);

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    /** 구현과 독립된 JDK 직접 호출로 IV‖암호문‖태그를 만든다 — 저장 형식 계약을 구현 밖에서 고정한다. */
    private static String seal(byte[] key, long memberId, byte[] secret) throws Exception {
        byte[] iv = filled(12, (byte) 3);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("member_totp:" + memberId).getBytes(StandardCharsets.US_ASCII));
        byte[] sealed = cipher.doFinal(secret);
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
    }

    private static String keyId(byte[] key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    @Test
    @DisplayName("암호화 → 복호화 왕복은 원래 비밀값이고, 저장 형식은 'v1:' 접두어 + 평문이 드러나지 않는 Base64다(PR1은 v1 쓰기 유지 — 이전 앱 호환)")
    void roundTrip() {
        TotpSecretCipher cipher = new TotpSecretCipher(KEY);
        byte[] secret = filled(20, (byte) 0x41);
        String stored = cipher.encrypt(7L, secret);
        assertThat(stored).startsWith("v1:").doesNotContain(Base32.encode(secret)).hasSizeLessThanOrEqualTo(255);
        assertThat(cipher.decrypt(7L, stored)).isEqualTo(secret);
    }

    @Test
    @DisplayName("같은 비밀값도 매번 다른 IV로 다른 암호문이 된다")
    void randomIv() {
        TotpSecretCipher cipher = new TotpSecretCipher(KEY);
        byte[] secret = filled(20, (byte) 1);
        assertThat(cipher.encrypt(7L, secret)).isNotEqualTo(cipher.encrypt(7L, secret));
    }

    @Test
    @DisplayName("다른 회원 행으로 옮겨 붙인 암호문(AAD 불일치)·다른 키·변조·형식 오류는 복호에 실패한다")
    void rejectsMismatch() {
        TotpSecretCipher cipher = new TotpSecretCipher(KEY);
        String stored = cipher.encrypt(7L, filled(20, (byte) 1));
        assertThatThrownBy(() -> cipher.decrypt(8L, stored)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(OTHER_KEY).decrypt(7L, stored)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        char last = stored.charAt(stored.length() - 2);
        String tampered = stored.substring(0, stored.length() - 2) + (last == 'A' ? 'B' : 'A') + stored.charAt(stored.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(7L, tampered)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "v3:" + stored.substring(3))).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "v1:!!")).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, null)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
    }

    @Test
    @DisplayName("키는 Base64로 정확히 32바이트여야 한다 — 비었거나 길이가 다르거나 Base64가 아니면 생성 실패")
    void rejectsInvalidKeys() {
        assertThatThrownBy(() -> new TotpSecretCipher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher("not base64!")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 키 교체 (PLAN-totp-key-rotation.md) ----

    @Test
    @DisplayName("옛 키는 선택이다 — 비었거나 null이면 없음, 형식이 틀리거나 현재 키와 같으면 생성 실패(FM3)")
    void previousKeyValidation() {
        assertThat(new TotpSecretCipher(KEY, null).previousKeyId()).isEmpty();
        assertThat(new TotpSecretCipher(KEY, " ").previousKeyId()).isEmpty();
        assertThatThrownBy(() -> new TotpSecretCipher(KEY, "not base64!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(KEY, Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(KEY, KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키ID는 원시 키 SHA-256의 앞 8바이트 16진 소문자 16자다 — 운영자가 printf|base64 -d|sha256sum|cut -c1-16으로 같은 값을 얻는다")
    void keyIds() throws Exception {
        TotpSecretCipher cipher = new TotpSecretCipher(KEY, OTHER_KEY);
        assertThat(cipher.currentKeyId()).isEqualTo(keyId(KEY_BYTES)).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(cipher.previousKeyId()).contains(keyId(OTHER_KEY_BYTES));
    }

    @Test
    @DisplayName("v1 암호문은 현재 키·옛 키 어느 쪽으로 만들어졌든 복호되고, 현재 키 전용 복호는 현재 키 것만 통과한다(FM2)")
    void readsV1WithEitherKey() throws Exception {
        byte[] secret = filled(20, (byte) 9);
        String underCurrent = "v1:" + seal(KEY_BYTES, 7L, secret);
        String underPrevious = "v1:" + seal(OTHER_KEY_BYTES, 7L, secret);
        TotpSecretCipher cipher = new TotpSecretCipher(KEY, OTHER_KEY);

        assertThat(cipher.decrypt(7L, underCurrent)).isEqualTo(secret);
        assertThat(cipher.decrypt(7L, underPrevious)).isEqualTo(secret);
        assertThat(cipher.decryptWithCurrentKey(7L, underCurrent)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher.decryptWithCurrentKey(7L, underPrevious)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        // 옛 키가 설정되지 않으면 옛 키 암호문은 읽히지 않는다
        assertThatThrownBy(() -> new TotpSecretCipher(KEY).decrypt(7L, underPrevious)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
    }

    @Test
    @DisplayName("v2:{키ID}: 암호문(PR2 쓰기 형식)은 키ID가 현재·옛 키와 같으면 그 키로 읽고, 모르는 키ID·대문자 변형은 형식 오류다(FM2 — PR2→PR1 롤백 호환)")
    void readsV2ByKeyId() throws Exception {
        byte[] secret = filled(20, (byte) 5);
        TotpSecretCipher cipher = new TotpSecretCipher(KEY, OTHER_KEY);
        String v2Current = "v2:" + keyId(KEY_BYTES) + ":" + seal(KEY_BYTES, 7L, secret);
        String v2Previous = "v2:" + keyId(OTHER_KEY_BYTES) + ":" + seal(OTHER_KEY_BYTES, 7L, secret);

        assertThat(cipher.decrypt(7L, v2Current)).isEqualTo(secret);
        assertThat(cipher.decrypt(7L, v2Previous)).isEqualTo(secret);
        assertThat(cipher.decryptWithCurrentKey(7L, v2Current)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher.decryptWithCurrentKey(7L, v2Previous)).isInstanceOf(TotpSecretCipher.DecryptionException.class);

        // 키ID와 실제 키가 어긋나면(현재 키ID로 표시했지만 옛 키로 봉인) 태그 검증으로 실패한다
        String mislabeled = "v2:" + keyId(KEY_BYTES) + ":" + seal(OTHER_KEY_BYTES, 7L, secret);
        assertThatThrownBy(() -> cipher.decrypt(7L, mislabeled)).isInstanceOf(TotpSecretCipher.DecryptionException.class);

        String unknown = "v2:" + "0".repeat(16) + ":" + seal(KEY_BYTES, 7L, secret);
        assertThatThrownBy(() -> cipher.decrypt(7L, unknown)).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "V2" + v2Current.substring(2))).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "v2:" + keyId(KEY_BYTES).toUpperCase(java.util.Locale.ROOT) + ":" + seal(KEY_BYTES, 7L, secret)))
                .isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "V1:" + seal(KEY_BYTES, 7L, secret))).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "v2:" + keyId(KEY_BYTES))).isInstanceOf(TotpSecretCipher.DecryptionException.class);
    }

    @Test
    @DisplayName("옛 키가 있어도 새 암호문은 현재 키 v1이다")
    void encryptsWithCurrentKeyOnly() {
        TotpSecretCipher rotating = new TotpSecretCipher(KEY, OTHER_KEY);
        String stored = rotating.encrypt(7L, filled(20, (byte) 2));
        assertThat(stored).startsWith("v1:");
        assertThat(new TotpSecretCipher(KEY).decrypt(7L, stored)).isEqualTo(filled(20, (byte) 2));
    }
}
