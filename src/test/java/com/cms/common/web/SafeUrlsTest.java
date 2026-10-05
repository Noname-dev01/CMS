package com.cms.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/admin", "/admin/menu/manage", "/admin/notice/manage?page=2&sort=id,desc", "/admin/x#frag", "/a%20b"
    })
    @DisplayName("같은 출처 경로: / 로 시작하는 값은 허용")
    void sameOriginPath_allowed(String url) {
        assertTrue(SafeUrls.isSameOriginPath(url));
        assertTrue(SafeUrls.isSafeMenuUrl(url));
        assertFalse(SafeUrls.isExternalHttpUrl(url));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "admin/x", "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<b>x</b>", "vbscript:x",
            "//evil.example", "//evil.example/x", "/\\evil.example", "\\\\evil.example", "/admin\\x",
            "/admin x", " /admin", "/admin\t", "/admin\nx", "/admin\u0000x", "/admin\u007fx", "mailto:a@b.c", "ftp://example.com/x"
    })
    @DisplayName("같은 출처 경로: 스킴·// 시작·역슬래시·공백·제어문자는 거부")
    void sameOriginPath_rejected(String url) {
        assertFalse(SafeUrls.isSameOriginPath(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com", "https://example.com", "HTTPS://Example.com/Path", "https://example.com/x?a=1#f",
            "https://example.com:8443/x", "https://sub.example.co.kr/a/b", "https://[::1]:8080/x", "https://example.com?a=1",
            "https://example.com/a@b"
    })
    @DisplayName("외부 http(s) 주소: 호스트가 있는 값은 허용 (경로 안의 @는 허용)")
    void externalHttpUrl_allowed(String url) {
        assertTrue(SafeUrls.isExternalHttpUrl(url));
        assertTrue(SafeUrls.isSafeMenuUrl(url));
        assertFalse(SafeUrls.isSameOriginPath(url));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "http://", "https://", "https:///x", "https://?a=1", "https://#f", "http:/example.com", "https:example.com",
            "https://admin@evil.example/x", "https://admin:pw@evil.example", "https://exa mple.com", "https://example.com/a b",
            "https://example.com\\@evil.example", "https://example.com/\u0000", "https://example.com/\n", " https://example.com",
            "https://example.com\t", "javascript:https://example.com", "ftp://example.com", "//example.com"
    })
    @DisplayName("외부 http(s) 주소: 호스트 없음·userinfo·공백·역슬래시·제어문자·다른 스킴은 거부")
    void externalHttpUrl_rejected(String url) {
        assertFalse(SafeUrls.isExternalHttpUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "data:text/html,x", "//evil.example", "admin/x", "https://admin@evil.example", "/\\evil"
    })
    @DisplayName("메뉴 URL: 위험 값은 경로·외부 어느 쪽으로도 허용되지 않는다")
    void safeMenuUrl_rejected(String url) {
        assertFalse(SafeUrls.isSafeMenuUrl(url));
    }
}
