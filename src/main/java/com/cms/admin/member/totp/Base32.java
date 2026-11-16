package com.cms.admin.member.totp;

import java.io.ByteArrayOutputStream;

/**
 * RFC 4648 Base32(패딩 없음). TOTP 비밀값과 복구 코드 표기에 쓴다 — 의존성을 늘리지 않으려고 직접 구현했다
 * (PLAN-totp-two-factor.md 결정 5). 디코딩은 대소문자를 구분하지 않고, 알파벳 밖 문자(패딩 '=' 포함)는 거부한다.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            int value = valueOf(text.charAt(i));
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int valueOf(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a';
        }
        if (c >= '2' && c <= '7') {
            return c - '2' + 26;
        }
        throw new IllegalArgumentException("Base32 문자가 아닙니다.");
    }
}
