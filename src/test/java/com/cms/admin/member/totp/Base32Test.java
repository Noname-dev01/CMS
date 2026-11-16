package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base32Test {

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("RFC 4648 §10 시험 벡터를 패딩 없이 인코딩하고 다시 디코딩한다")
    @CsvSource({
            "'', ''",
            "f, MY",
            "fo, MZXQ",
            "foo, MZXW6",
            "foob, MZXW6YQ",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI"
    })
    void rfc4648Vectors(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);
        assertThat(Base32.encode(bytes)).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("디코딩은 소문자를 받아들인다(인증 앱·사람이 소문자로 옮겨 적는 경우)")
    void decodeAcceptsLowercase() {
        assertThat(Base32.decode("mzxw6ytboi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("Base32 알파벳 밖의 문자는 거부한다")
    void decodeRejectsInvalidCharacters() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZXW6=")).isInstanceOf(IllegalArgumentException.class);
    }
}
