package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCodesTest {

    @Test
    @DisplayName("10개를 만들고, 각각 120비트(15바이트) = Base32 24자를 4자씩 하이픈 5개로 묶어 표시한다")
    void generatesTenFormattedCodes() {
        List<String> codes = RecoveryCodes.generate(new SecureRandom());
        assertThat(codes).hasSize(RecoveryCodes.COUNT).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        for (String code : codes) {
            assertThat(code).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}");
            assertThat(Base32.decode(code.replace("-", ""))).hasSize(RecoveryCodes.ENTROPY_BYTES).hasSize(15);
        }
    }

    @Test
    @DisplayName("해시는 하이픈·공백·대소문자와 무관하게 같고, SHA-256 16진 64자다")
    void hashNormalizes() {
        String code = "ABCD-EFGH-IJKL-MNOP-QRST-UVWX";
        String hash = RecoveryCodes.hash(code);
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(RecoveryCodes.hash(" abcd efgh-ijkl mnop qrst-uvwx ")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCDEFGHIJKLMNOPQRSTUVWX")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCD-EFGH-IJKL-MNOP-QRST-UVWY")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("정규화 결과가 24자 Base32가 아니면 복구 코드 형식이 아니다(판정 대상에서 뺀다)")
    void formatCheck() {
        assertThat(RecoveryCodes.looksLikeRecoveryCode("abcd-efgh-ijkl-mnop-qrst-uvwx")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("123456")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCD-EFGH-IJKL-MNOP-QRST-UVW1")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCD-EFGH-IJKL-MNOP-QRST")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode(null)).isFalse();
    }
}
