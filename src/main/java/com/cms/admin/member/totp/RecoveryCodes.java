package com.cms.admin.member.totp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 2단계 인증 복구 코드 — 10개, 각 120비트(15바이트) 무작위를 Base32 24자로 표기({@code XXXX-XXXX-XXXX-XXXX-XXXX-XXXX}).
 *
 * <p>저장은 정규화(공백·하이픈 제거, 대문자) 후 SHA-256 16진수다. 120비트는 NIST SP 800-63B-4 §3.1.2.2가 솔트 없는
 * 빠른 해시를 허용하는 lookup secret 최소 112비트 이상이라 DB 유출 시 오프라인 대입이 비현실적이다
 * (PLAN-totp-two-factor.md 결정 7 — 50비트안은 리뷰에서 기각).
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    static final int ENTROPY_BYTES = 15;
    private static final int ENCODED_LENGTH = 24;
    private static final int GROUP = 4;

    private RecoveryCodes() {
    }

    public static List<String> generate(SecureRandom random) {
        List<String> codes = new ArrayList<>(COUNT);
        while (codes.size() < COUNT) {
            byte[] bytes = new byte[ENTROPY_BYTES];
            random.nextBytes(bytes);
            String code = format(Base32.encode(bytes));
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 정규화한 값이 24자 Base32인가 — 6자리 TOTP 코드와 구분하는 데 쓴다. */
    public static boolean looksLikeRecoveryCode(String input) {
        if (input == null) {
            return false;
        }
        String normalized = normalize(input);
        if (normalized.length() != ENCODED_LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '2' && c <= '7'))) {
                return false;
            }
        }
        return true;
    }

    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(code).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static String normalize(String input) {
        return input.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String format(String encoded) {
        StringBuilder out = new StringBuilder(encoded.length() + encoded.length() / GROUP);
        for (int i = 0; i < encoded.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                out.append('-');
            }
            out.append(encoded.charAt(i));
        }
        return out.toString();
    }
}
