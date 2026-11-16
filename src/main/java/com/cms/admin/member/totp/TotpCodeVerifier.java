package com.cms.admin.member.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.OptionalLong;

/**
 * RFC 6238 TOTP(HMAC-SHA1, 30초, 6자리, T0 = 0) 판정. 인증 앱 기본값과 같다(PLAN-totp-two-factor.md 결정 5).
 *
 * <p>현재 스텝 ±1(앞뒤 30초)까지 허용하되, {@code lastUsedStep} 이하 스텝은 맞아도 거부한다 — 같은 코드의 재사용 차단.
 * 시각은 주입된 {@link Clock}에서만 읽는다(시험에서 경계를 고정하기 위해).
 */
public class TotpCodeVerifier {

    static final long PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULUS = 1_000_000;
    private static final int ALLOWED_SKEW_STEPS = 1;

    private final Clock clock;

    public TotpCodeVerifier(Clock clock) {
        this.clock = clock;
    }

    /** 현재 시각의 30초 스텝 번호. */
    public long currentStep() {
        return Math.floorDiv(clock.instant().getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * 코드가 맞으면 일치한 스텝을, 아니면 빈 값을 돌려준다. 형식이 6자리 ASCII 숫자가 아니면 계산하지 않는다.
     *
     * @param lastUsedStep 마지막으로 통과한 스텝(없으면 음수)
     */
    public OptionalLong verify(byte[] secret, String code, long lastUsedStep) {
        if (!isSixAsciiDigits(code)) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = currentStep();
        OptionalLong matched = OptionalLong.empty();
        // 일치 여부와 무관하게 세 스텝을 모두 계산·비교한다(상수 시간 비교, 분기 수 고정).
        for (long step = current - ALLOWED_SKEW_STEPS; step <= current + ALLOWED_SKEW_STEPS; step++) {
            byte[] expected = codeAt(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given) && step > lastUsedStep && matched.isEmpty()) {
                matched = OptionalLong.of(step);
            }
        }
        return matched;
    }

    /**
     * 정답이지만 이미 쓴 스텝({@code <= lastUsedStep}, 허용 범위 ±1 안)의 코드인가 — 등록 직후 같은 30초 안에 같은 코드로 다시
     * 로그인하는 경우다. 통과시키지는 않되 실패로 세지 않고 "다음 코드를 기다리라"고 안내하는 데 쓴다(재사용은 어차피 통과하지 못해 대입에 도움이 안 된다).
     */
    public boolean isReusedCode(byte[] secret, String code, long lastUsedStep) {
        if (!isSixAsciiDigits(code)) {
            return false;
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = currentStep();
        boolean reused = false;
        for (long step = current - ALLOWED_SKEW_STEPS; step <= current + ALLOWED_SKEW_STEPS; step++) {
            if (step <= lastUsedStep && MessageDigest.isEqual(codeAt(secret, step).getBytes(StandardCharsets.US_ASCII), given)) {
                reused = true;
            }
        }
        return reused;
    }

    /** 6자리 ASCII 숫자인가 — 전각 숫자 등 {@link Character#isDigit}이 받는 문자는 거부한다. */
    public static boolean isSixAsciiDigits(String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** RFC 4226 HOTP(동적 절단) 값을 6자리 문자열로. */
    public static String codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format(java.util.Locale.ROOT, "%0" + DIGITS + "d", binary % MODULUS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1을 사용할 수 없습니다.", e);
        }
    }
}
