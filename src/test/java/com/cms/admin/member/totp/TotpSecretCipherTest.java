package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(filled(32, (byte) 7));

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @Test
    @DisplayName("암호화 → 복호화 왕복은 원래 비밀값이고, 저장 형식은 'v1:' 접두어 + 평문이 드러나지 않는 Base64다")
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
        assertThatThrownBy(() -> cipher.decrypt(7L, "v2:" + stored.substring(3))).isInstanceOf(TotpSecretCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(7L, "v1:!!")).isInstanceOf(TotpSecretCipher.DecryptionException.class);
    }

    @Test
    @DisplayName("키는 Base64로 정확히 32바이트여야 한다 — 비었거나 길이가 다르거나 Base64가 아니면 생성 실패")
    void rejectsInvalidKeys() {
        assertThatThrownBy(() -> new TotpSecretCipher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TotpSecretCipher("not base64!")).isInstanceOf(IllegalArgumentException.class);
    }
}
