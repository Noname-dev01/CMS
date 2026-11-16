package com.cms.admin.member.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpCodeVerifierTest {

    /** RFC 6238 부록 B의 SHA1 시드("12345678901234567890" ASCII 20바이트). */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static TotpCodeVerifier at(long epochSeconds) {
        return new TotpCodeVerifier(Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC));
    }

    @ParameterizedTest(name = "T={0} → {1}")
    @DisplayName("RFC 6238 부록 B SHA1 벡터의 8자리 값을 6자리로 자른 코드와 같다")
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"
    })
    void rfc6238Sha1Vectors(long epochSeconds, String eightDigits) {
        String expected = eightDigits.substring(2); // 8자리 = (truncated % 10^8) → 6자리 = 끝 6자리
        assertThat(TotpCodeVerifier.codeAt(RFC_SECRET, epochSeconds / 30)).isEqualTo(expected);
        assertThat(at(epochSeconds).verify(RFC_SECRET, expected, -1)).hasValue(epochSeconds / 30);
    }

    @Test
    @DisplayName("±1 스텝(앞뒤 30초)까지 허용하고 ±2 스텝은 거부한다")
    void allowsOneStepSkew() {
        long now = 1_234_567_890L;
        long step = now / 30;
        TotpCodeVerifier verifier = at(now);
        assertThat(verifier.verify(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step - 1), -1)).hasValue(step - 1);
        assertThat(verifier.verify(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step + 1), -1)).hasValue(step + 1);
        assertThat(verifier.verify(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step - 2), -1)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step + 2), -1)).isEmpty();
    }

    @Test
    @DisplayName("마지막으로 쓴 스텝 이하의 코드는 맞아도 거부한다(재사용 차단) — 그보다 뒤 스텝은 통과")
    void rejectsStepsAtOrBeforeLastUsed() {
        long now = 1_234_567_890L;
        long step = now / 30;
        TotpCodeVerifier verifier = at(now);
        String current = TotpCodeVerifier.codeAt(RFC_SECRET, step);
        assertThat(verifier.verify(RFC_SECRET, current, step)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step - 1), step - 1)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, current, step - 1)).hasValue(step);
    }

    @Test
    @DisplayName("이미 쓴 스텝(허용 범위 안)과 일치하는 코드는 '재사용'으로 구분한다 — 새 스텝 정답·오답·범위 밖은 재사용이 아니다")
    void detectsReusedCode() {
        long now = 1_234_567_890L;
        long step = now / 30;
        TotpCodeVerifier verifier = at(now);
        assertThat(verifier.isReusedCode(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step), step)).isTrue();
        assertThat(verifier.isReusedCode(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step - 1), step)).isTrue();
        assertThat(verifier.isReusedCode(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step + 1), step)).as("아직 안 쓴 다음 스텝").isFalse();
        assertThat(verifier.isReusedCode(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step), step - 1)).as("새 정답").isFalse();
        assertThat(verifier.isReusedCode(RFC_SECRET, TotpCodeVerifier.codeAt(RFC_SECRET, step - 2), step)).as("범위 밖").isFalse();
        assertThat(verifier.isReusedCode(RFC_SECRET, "abc", step)).isFalse();
    }

    @Test
    @DisplayName("6자리 숫자가 아닌 입력은 판정하지 않는다")
    void rejectsMalformedCodes() {
        TotpCodeVerifier verifier = at(59);
        assertThat(verifier.verify(RFC_SECRET, "28708", -1)).isEqualTo(OptionalLong.empty());
        assertThat(verifier.verify(RFC_SECRET, "2870821", -1)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, "28708a", -1)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, null, -1)).isEmpty();
        assertThat(verifier.verify(RFC_SECRET, "２８７０８２", -1)).as("전각 숫자").isEmpty();
    }
}
