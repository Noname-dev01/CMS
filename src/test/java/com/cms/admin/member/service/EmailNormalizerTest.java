package com.cms.admin.member.service;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailNormalizerTest {

    @Test
    @DisplayName("null은 그대로 반환")
    void normalize_null_returnsNull() {
        assertNull(EmailNormalizer.normalize(null));
    }

    @Test
    @DisplayName("앞뒤 공백을 제거한다")
    void normalize_trimsWhitespace() {
        assertEquals("admin@test.com", EmailNormalizer.normalize("  admin@test.com  "));
    }

    @Test
    @DisplayName("Locale.ROOT 기준으로 소문자화한다")
    void normalize_lowercasesWithRootLocale() {
        assertEquals("admin@test.com", EmailNormalizer.normalize("Admin@Test.COM"));
    }

    @Test
    @DisplayName("정규화 후 길이가 컬럼 길이(100)를 넘으면 InvalidRequestException")
    void normalize_normalizedLengthExceedsColumnLimit_throws() {
        // İ(U+0130).toLowerCase(Locale.ROOT) == "i̇"(2자) — 100자 입력이 정규화 후 200자로 늘어난다(실측 확인).
        String oversized = "İ".repeat(100);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> EmailNormalizer.normalize(oversized));

        assertEquals("이메일이 너무 깁니다.", exception.getMessage());
    }

    @Test
    @DisplayName("정규화 후 길이가 정확히 100자면 통과")
    void normalize_normalizedLengthExactlyAtLimit_succeeds() {
        String exactlyHundred = "a".repeat(100);

        assertEquals(exactlyHundred, EmailNormalizer.normalize(exactlyHundred));
    }

    @Test
    @DisplayName("고립 서로게이트를 포함한 이메일은 InvalidRequestException")
    void normalize_loneSurrogate_throws() {
        String loneSurrogate = "\uD800@a.co";

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> EmailNormalizer.normalize(loneSurrogate));

        assertEquals("이메일 형식이 올바르지 않습니다.", exception.getMessage());
    }
}
