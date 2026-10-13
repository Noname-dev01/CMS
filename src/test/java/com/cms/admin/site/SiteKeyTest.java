package com.cms.admin.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 사이트 키 형식·예약어 규칙(PLAN-site-menu.md D2). */
class SiteKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"ab", "main", "news-2", "a1"})
    @DisplayName("형식 허용: 소문자·숫자·하이픈, 2~30자")
    void validKeys(String key) {
        assertThat(SiteKey.isValid(key)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "Main", "news_2", "-ab", "ab-", "a--b", "a b", "a.b", "favicon.ico", "한글", "a/b", "ab\n"})
    @DisplayName("형식 거부: 1자·31자 이상·대문자·밑줄·처음/끝/연속 하이픈·공백·점·슬래시·비ASCII·개행")
    void invalidKeys(String key) {
        assertThat(SiteKey.isValid(key)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "actuator", "error", "swagger-ui", "v3", "api", "notices", "boards", "pages", "faq",
            "inquiry", "content-images", "banners", "popups", "css", "js", "img", "vendor"})
    @DisplayName("예약어: 현재 공개·관리·정적 경로의 첫 세그먼트")
    void reservedKeys(String key) {
        assertThat(SiteKey.isValid(key)).isTrue();   // 형식은 맞지만
        assertThat(SiteKey.isReserved(key)).isTrue(); // 예약어라 쓸 수 없다
    }

    @Test
    @DisplayName("길이 경계: 30자는 허용, 31자는 거부")
    void lengthBoundary() {
        assertThat(SiteKey.isValid("x".repeat(30))).isTrue();
        assertThat(SiteKey.isValid("x".repeat(31))).isFalse();
    }

    @Test
    @DisplayName("시드 공개 사이트 키 main은 예약어가 아니다")
    void seedKeyIsNotReserved() {
        assertThat(SiteKey.isReserved("main")).isFalse();
    }
}
