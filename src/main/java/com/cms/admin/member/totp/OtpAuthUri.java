package com.cms.admin.member.totp;

import java.nio.charset.StandardCharsets;

/**
 * 인증 앱 등록용 {@code otpauth://} URI. RFC가 아니라 Google의 "Key Uri Format"
 * (https://github.com/google/google-authenticator/wiki/Key-Uri-Format)이 사실상 표준이다.
 *
 * <p>레이블 {@code issuer:account}의 두 부분과 issuer 파라미터는 RFC 3986 퍼센트 인코딩(공백 {@code %20} — 폼 인코딩의
 * {@code +}가 아니다)이고, 콜론은 어느 쪽에도 들어갈 수 없다. issuer는 설정값이라 기동 시 검증하고({@link #validateIssuer}),
 * 계정 이름(회원 아이디)의 콜론은 {@code _}로 바꾼다 — 인증 앱에 표시만 되고 판정에는 쓰이지 않는다(PLAN 결정 5).
 */
public final class OtpAuthUri {

    private OtpAuthUri() {
    }

    public static String build(String issuer, String accountName, String base32Secret) {
        validateIssuer(issuer);
        String encodedIssuer = encode(issuer);
        return "otpauth://totp/" + encodedIssuer + ":" + encode(accountName.replace(':', '_'))
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    public static void validateIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("TOTP issuer(cms.totp.issuer)가 비어 있습니다.");
        }
        if (issuer.indexOf(':') >= 0) {
            throw new IllegalArgumentException("TOTP issuer(cms.totp.issuer)에는 콜론(:)을 쓸 수 없습니다.");
        }
    }

    /** RFC 3986 unreserved(ALPHA / DIGIT / "-" / "." / "_" / "~") 밖의 모든 바이트를 %XX(대문자)로. */
    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0x0F, 16)));
            }
        }
        return out.toString();
    }
}
